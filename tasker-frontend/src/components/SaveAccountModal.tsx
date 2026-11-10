import { useEffect, useState, type FormEvent } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { ApiError, requestEmailVerification } from '../api';
import { useAuth } from '../auth/AuthContext';
import { TELEGRAM_LINK_URL } from '../auth/authApi';
import { MailIcon, TelegramIcon } from '../auth/loginIcons';
import { usePublicConfig } from '../publicConfig';
import styles from './SaveAccountModal.module.css';

interface SaveAccountModalProps {
  open: boolean;
  onClose: () => void;
}

type Step = 'choose' | 'email' | 'emailSent';

/** Maps a failed verification request to the one message the user can act on. */
function emailErrorKey(e: unknown): string {
  if (e instanceof ApiError) {
    // The address already backs another account; the server refuses rather than merge.
    if (e.status === 409) return 'saveAccountModal.errors.emailTaken';
    if (e.code === 'BLOCKED_EMAIL_DOMAIN') return 'login.errors.blockedEmailDomain';
    if (e.status === 429) return 'saveAccountModal.errors.rateLimited';
  }
  return 'saveAccountModal.errors.sendFailed';
}

/**
 * Claims an unclaimed (demo) account by linking a login method, without sending the user through
 * the full settings page. It drives the same endpoints Settings uses:
 *  - Telegram: the OIDC link round-trip (`/api/auth/telegram/link/start`). That redirect lands on
 *    Settings, which is where the "open the bot chat" step lives, so we don't duplicate it here.
 *  - Email: requests a verification link; following it verifies the address, which claims the
 *    account and attaches an email login.
 *
 * The verification link usually opens in another tab, so while we wait for it the modal re-checks
 * `/me` whenever this tab regains focus and switches to a "saved" state once the account is claimed.
 */
export function SaveAccountModal({ open, onClose }: SaveAccountModalProps) {
  const { t } = useTranslation();
  const config = usePublicConfig();
  const { state: authState, refresh } = useAuth();
  const claimed = authState.status === 'authenticated' && authState.user.claimed;

  const [step, setStep] = useState<Step>('choose');
  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [wasOpen, setWasOpen] = useState(false);

  // Reset on the closed→open edge (adjust-state-during-render, as in FeedbackModal).
  if (open && !wasOpen) {
    setWasOpen(true);
    setStep('choose');
    setEmail('');
    setSending(false);
    setError(null);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const waitingForLink = open && step === 'emailSent' && !claimed;
  useEffect(() => {
    if (!waitingForLink) return;
    const recheck = () => { void refresh(); };
    window.addEventListener('focus', recheck);
    return () => window.removeEventListener('focus', recheck);
  }, [waitingForLink, refresh]);

  const submitEmail = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = email.trim();
    if (!trimmed || sending) return;
    setSending(true);
    setError(null);
    try {
      await requestEmailVerification(trimmed, { emitErrors: false });
      setStep('emailSent');
    } catch (err) {
      console.error('Failed to send verification email', err);
      setError(t(emailErrorKey(err)));
    } finally {
      setSending(false);
    }
  };

  const hasMethod = config.login.telegram || config.login.email;

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className={`modal ${styles.modal}`} role="dialog" aria-modal="true" aria-label={t('saveAccountModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{claimed ? t('saveAccountModal.savedTitle') : t('saveAccountModal.title')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('saveAccountModal.close')}>×</button>
        </div>

        <div className="modal__body">
          {claimed ? (
            <p className={styles.lead} role="status">{t('saveAccountModal.savedBody')}</p>
          ) : !hasMethod ? (
            <p className={styles.lead}>{t('saveAccountModal.noMethods')}</p>
          ) : step === 'emailSent' ? (
            <>
              <p className={styles.lead} role="status">
                <Trans
                  i18nKey="saveAccountModal.emailSent"
                  values={{ email: email.trim() }}
                  components={{ strong: <strong /> }}
                />
              </p>
              <button type="button" className="link-btn" onClick={() => setStep('email')}>
                {t('saveAccountModal.useDifferentEmail')}
              </button>
            </>
          ) : (
            <>
              <p className={styles.lead}>{t('saveAccountModal.intro')}</p>
              <div className={styles.methods}>
                {config.login.telegram && (
                  <a className={`btn btn--ghost ${styles.method}`} href={TELEGRAM_LINK_URL}>
                    <TelegramIcon /> {t('saveAccountModal.telegram')}
                  </a>
                )}
                {config.login.email && (step === 'email' ? (
                  <form className={styles.emailForm} onSubmit={submitEmail}>
                    <input
                      type="email"
                      className="field__input"
                      aria-label={t('saveAccountModal.emailLabel')}
                      placeholder={t('saveAccountModal.emailPlaceholder')}
                      autoComplete="email"
                      value={email}
                      onChange={e => setEmail(e.target.value)}
                      autoFocus
                      required
                    />
                    <button type="submit" className="btn btn--primary" disabled={sending || !email.trim()}>
                      {sending ? t('saveAccountModal.sending') : t('saveAccountModal.sendLink')}
                    </button>
                  </form>
                ) : (
                  <button type="button" className={`btn btn--ghost ${styles.method}`} onClick={() => setStep('email')}>
                    <MailIcon /> {t('saveAccountModal.email')}
                  </button>
                ))}
              </div>
              {error && <p className="settings-error" role="alert">{error}</p>}
            </>
          )}
        </div>

        {claimed && (
          <div className="modal__footer">
            <button className="btn btn--primary" onClick={onClose}>{t('saveAccountModal.done')}</button>
          </div>
        )}
      </div>
    </div>
  );
}

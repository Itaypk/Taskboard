import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sendFeedback, ApiError } from '../api';
import styles from './FeedbackModal.module.css';

interface FeedbackModalProps {
  open: boolean;
  onClose: () => void;
}

const MAX_MESSAGE = 5000;

/**
 * "Send feedback" form reached from the profile menu: a free-text box plus an optional reply
 * address. Submission posts to `/api/v1/feedback`, which emails it to the configured recipient.
 */
export function FeedbackModal({ open, onClose }: FeedbackModalProps) {
  const { t } = useTranslation();
  const [message, setMessage] = useState('');
  const [replyEmail, setReplyEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [wasOpen, setWasOpen] = useState(false);

  // Reset on the closed→open edge (adjust-state-during-render pattern, matching SettingsModal),
  // so a fresh form greets the user each time the modal is reopened.
  if (open && !wasOpen) {
    setWasOpen(true);
    setMessage('');
    setReplyEmail('');
    setSubmitting(false);
    setSent(false);
    setError(null);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const canSubmit = message.trim().length > 0 && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await sendFeedback(message.trim(), replyEmail.trim() || undefined, { emitErrors: false });
      setSent(true);
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage : t('feedbackModal.errors.generic');
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={t('feedbackModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{t('feedbackModal.title')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('feedbackModal.close')}>×</button>
        </div>

        {sent ? (
          <div className="modal__body">
            <p className={styles.thanks}>{t('feedbackModal.thanks')}</p>
          </div>
        ) : (
          <div className="modal__body">
            <p className="settings-hint">
              {t('feedbackModal.hint')}
            </p>
            <div className="field">
              <label className="field__label" htmlFor="feedback-message">{t('feedbackModal.messageLabel')}</label>
              <textarea
                id="feedback-message"
                className="field__textarea"
                rows={6}
                maxLength={MAX_MESSAGE}
                value={message}
                onChange={e => setMessage(e.target.value)}
                placeholder={t('feedbackModal.messagePlaceholder')}
                autoFocus
              />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="feedback-email">{t('feedbackModal.emailLabel')}</label>
              <input
                id="feedback-email"
                type="email"
                className="field__input"
                value={replyEmail}
                onChange={e => setReplyEmail(e.target.value)}
                placeholder={t('feedbackModal.emailPlaceholder')}
              />
            </div>
            {error && <p className={styles.error}>{error}</p>}
          </div>
        )}

        <div className="modal__footer">
          {sent ? (
            <button className="btn btn--primary" onClick={onClose}>{t('feedbackModal.done')}</button>
          ) : (
            <>
              <button className="btn btn--ghost" onClick={onClose} disabled={submitting}>{t('feedbackModal.cancel')}</button>
              <button className="btn btn--primary" onClick={handleSubmit} disabled={!canSubmit}>
                {submitting ? t('feedbackModal.sending') : t('feedbackModal.send')}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

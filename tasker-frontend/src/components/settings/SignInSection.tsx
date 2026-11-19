import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { requestEmailVerification } from '../../api';
import type { UserSettings } from '../../types';
import { ActiveSessions } from '../ActiveSessions';
import { ConnectedAccounts } from '../ConnectedAccounts';

interface Props {
  settings: Pick<UserSettings, 'email' | 'emailVerified'>;
}

/**
 * The email address (a verified one is a login method and receives calendar invitations), the
 * other linked login methods, and the devices currently signed in. Each part saves on its own.
 */
export function SignInSection({ settings }: Props) {
  const { t } = useTranslation();
  const [emailInput, setEmailInput] = useState(settings.email ?? '');
  const [verificationSent, setVerificationSent] = useState(false);
  const [sendingVerification, setSendingVerification] = useState(false);
  const verifiedAndUnchanged = settings.emailVerified && !!settings.email && settings.email === emailInput.trim();

  const handleSendVerification = async () => {
    if (!emailInput.trim()) return;
    setSendingVerification(true);
    try {
      await requestEmailVerification(emailInput.trim());
      setVerificationSent(true);
    } catch (e) {
      console.error('Failed to send verification email', e);
    } finally {
      setSendingVerification(false);
    }
  };

  return (
    <>
      <div className="field">
        <label className="field__label" htmlFor="settings-email">{t('settingsModal.general.emailAddress')}</label>
        {verifiedAndUnchanged ? (
          <p className="settings-hint settings-hint--verified">
            {t('settingsModal.general.emailVerified', { email: settings.email })}
          </p>
        ) : (
          <p className="settings-hint">
            {t('settingsModal.general.emailHint')}
            {settings.email && !settings.emailVerified && ` ${t('settingsModal.general.emailNotVerified')}`}
          </p>
        )}
        <div className="settings-email-row">
          <input
            id="settings-email"
            className="field__input"
            type="email"
            value={emailInput}
            onChange={e => { setEmailInput(e.target.value); setVerificationSent(false); }}
            placeholder={t('settingsModal.general.emailPlaceholder')}
            autoComplete="email"
          />
          <button
            type="button"
            className="btn btn--ghost"
            onClick={handleSendVerification}
            disabled={sendingVerification || !emailInput.trim() || verifiedAndUnchanged}
          >
            {sendingVerification ? t('settingsModal.general.sending') : verificationSent ? t('settingsModal.general.sent') : t('settingsModal.general.sendVerification')}
          </button>
        </div>
        {verificationSent && (
          <p className="settings-hint">{t('settingsModal.general.verificationSentHint')}</p>
        )}
      </div>

      <ConnectedAccounts />
      <ActiveSessions />
    </>
  );
}

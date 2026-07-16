import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import {
    fetchIdentities,
    fetchMe,
    unlinkIdentity,
    TELEGRAM_LINK_URL,
    type LinkedIdentity,
} from '../auth/authApi';
import { ApiError } from '../api';

const PROVIDER_LABEL_KEYS: Record<string, string> = {
    telegram: 'connectedAccounts.providers.telegram',
    email: 'connectedAccounts.providers.email',
    google: 'connectedAccounts.providers.google',
};

// Surfaced after the Telegram link redirect lands back on /settings?telegramLink=<code>.
const LINK_NOTICE_KEYS: Record<string, string> = {
    conflict: 'connectedAccounts.linkNotices.conflict',
    exists: 'connectedAccounts.linkNotices.exists',
    failed: 'connectedAccounts.linkNotices.failed',
    unavailable: 'connectedAccounts.linkNotices.unavailable',
};

function readLinkNoticeKey(): string | null {
    const code = new URLSearchParams(window.location.search).get('telegramLink');
    return code && code !== 'success' ? LINK_NOTICE_KEYS[code] ?? LINK_NOTICE_KEYS.failed : null;
}

/**
 * "Connected accounts" — the login methods linked to the current user. Lets the user link a
 * Telegram account (via the OIDC redirect flow) and unlink any method, guarded so they can't
 * remove their last way to sign in. Email is linked through the verify-email field above; it
 * appears here once verified. Provider-agnostic so a future Google method just shows up as a row.
 */
export function ConnectedAccounts() {
    const { t } = useTranslation();
    const { setUser } = useAuth();
    const [identities, setIdentities] = useState<LinkedIdentity[] | null>(null);
    const [busy, setBusy] = useState(false);
    const initialNoticeKey = readLinkNoticeKey();
    const [error, setError] = useState<string | null>(initialNoticeKey ? t(initialNoticeKey) : null);

    const refresh = useCallback(async () => {
        try {
            setIdentities(await fetchIdentities());
        } catch (e) {
            console.error('Failed to load connected accounts', e);
        }
    }, []);

    // Load once on mount; setState lives in the async continuation, not the effect body.
    useEffect(() => {
        let cancelled = false;
        fetchIdentities()
            .then(ids => { if (!cancelled) setIdentities(ids); })
            .catch(e => console.error('Failed to load connected accounts', e));
        return () => { cancelled = true; };
    }, []);

    // Drop the telegramLink notice from the URL so it doesn't survive a refresh.
    useEffect(() => {
        if (new URLSearchParams(window.location.search).has('telegramLink')) {
            window.history.replaceState(null, '', window.location.pathname);
        }
    }, []);

    const hasTelegram = identities?.some(i => i.provider === 'telegram') ?? false;

    const handleUnlink = async (provider: string) => {
        setBusy(true);
        setError(null);
        try {
            await unlinkIdentity(provider);
            // Refresh both the identity list and the cached user (cleared telegram/email fields).
            await refresh();
            const me = await fetchMe();
            if (me) setUser(me);
        } catch (e) {
            if (e instanceof ApiError && e.status === 409) {
                setError(t('connectedAccounts.errors.lastMethod'));
            } else if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('connectedAccounts.errors.unlinkFailed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const canUnlink = (identities?.length ?? 0) > 1;

    return (
        <div className="field">
            <label className="field__label">{t('connectedAccounts.label')}</label>
            <p className="settings-hint">{t('connectedAccounts.hint')}</p>

            <ul className="connected-accounts">
                {identities?.map(identity => (
                    <li key={identity.provider} className="connected-account">
                        <span className="connected-account__name">
                            {PROVIDER_LABEL_KEYS[identity.provider] ? t(PROVIDER_LABEL_KEYS[identity.provider]) : identity.provider}
                        </span>
                        <button
                            type="button"
                            className="btn btn--ghost connected-account__action"
                            onClick={() => handleUnlink(identity.provider)}
                            disabled={busy || !canUnlink}
                            title={canUnlink ? undefined : t('connectedAccounts.linkAnotherFirst')}
                        >
                            {t('connectedAccounts.unlink')}
                        </button>
                    </li>
                ))}
                {identities?.length === 0 && (
                    <li className="settings-hint">{t('connectedAccounts.none')}</li>
                )}
            </ul>

            {!hasTelegram && (
                <a className="btn btn--ghost" href={TELEGRAM_LINK_URL}>
                    {t('connectedAccounts.linkTelegram')}
                </a>
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

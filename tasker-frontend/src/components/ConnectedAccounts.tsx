import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import {
    fetchIdentities,
    fetchMe,
    fetchTelegramBot,
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
// `success` is absent deliberately — it gets the "open the chat" panel below, not an error line.
const LINK_ERROR_KEYS: Record<string, string> = {
    conflict: 'connectedAccounts.linkNotices.conflict',
    exists: 'connectedAccounts.linkNotices.exists',
    failed: 'connectedAccounts.linkNotices.failed',
    unavailable: 'connectedAccounts.linkNotices.unavailable',
};

function readLinkCode(): string | null {
    return new URLSearchParams(window.location.search).get('telegramLink');
}

/**
 * "Connected accounts" — the login methods linked to the current user. Lets the user link a
 * Telegram account (via the OIDC redirect flow) and unlink any method, guarded so they can't
 * remove their last way to sign in. Email is linked through the verify-email field above; it
 * appears here once verified. Provider-agnostic so a future Google method just shows up as a row.
 *
 * Linking Telegram needs one step the OIDC round-trip cannot do for the user: Telegram refuses to
 * let a bot message anyone who has not written to it first, so a linked-but-never-opened chat is
 * unreachable and the weekly planning conversation silently never arrives. A successful link
 * therefore ends on an explicit "open the chat" panel rather than the silent button-disappears
 * state it used to, and the bot link stays available afterwards for anyone who missed it.
 */
export function ConnectedAccounts() {
    const { t } = useTranslation();
    const { setUser } = useAuth();
    const [identities, setIdentities] = useState<LinkedIdentity[] | null>(null);
    const [busy, setBusy] = useState(false);
    const [botUsername, setBotUsername] = useState<string | null>(null);
    // Read once on mount: the effect below scrubs the query string, so a per-render read would
    // come back null and the panel would vanish on the next state change.
    const [linkCode] = useState(readLinkCode);
    const justLinked = linkCode === 'success';
    const initialErrorKey = linkCode && !justLinked ? LINK_ERROR_KEYS[linkCode] ?? LINK_ERROR_KEYS.failed : null;
    const [error, setError] = useState<string | null>(initialErrorKey ? t(initialErrorKey) : null);

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

    // Only worth a request once there is a chat to point at — an email-only account never needs it.
    const needsBotHandle = justLinked || hasTelegram;
    useEffect(() => {
        if (!needsBotHandle) return;
        let cancelled = false;
        fetchTelegramBot()
            .then(info => { if (!cancelled) setBotUsername(info.username); })
            .catch(e => console.error('Failed to load Telegram bot handle', e));
        return () => { cancelled = true; };
    }, [needsBotHandle]);

    const botUrl = botUsername ? `https://t.me/${botUsername}` : null;
    const openChatLabel = botUsername
        ? t('connectedAccounts.openTelegramChat', { bot: `@${botUsername}` })
        : null;

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

            {!hasTelegram && !justLinked && (
                <a className="btn btn--ghost" href={TELEGRAM_LINK_URL}>
                    {t('connectedAccounts.linkTelegram')}
                </a>
            )}

            {justLinked && (
                <div className="settings-notice" role="status">
                    <p className="settings-notice__text">{t('connectedAccounts.telegramNextStep')}</p>
                    {botUrl && openChatLabel && (
                        <a className="btn btn--primary" href={botUrl} target="_blank" rel="noreferrer">
                            {openChatLabel}
                        </a>
                    )}
                </div>
            )}

            {/* Permanent way back to the chat for anyone who navigated past the panel above. */}
            {!justLinked && hasTelegram && botUrl && openChatLabel && (
                <p className="settings-hint">
                    <a href={botUrl} target="_blank" rel="noreferrer">{openChatLabel}</a>
                </p>
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

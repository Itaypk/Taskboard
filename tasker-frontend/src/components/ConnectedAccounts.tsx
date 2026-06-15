import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
    fetchIdentities,
    fetchMe,
    unlinkIdentity,
    TELEGRAM_LINK_URL,
    type LinkedIdentity,
} from '../auth/authApi';
import { ApiError } from '../api';

const PROVIDER_LABELS: Record<string, string> = {
    telegram: 'Telegram',
    email: 'Email',
    google: 'Google',
};

// Surfaced after the Telegram link redirect lands back on /settings?telegramLink=<code>.
const LINK_NOTICES: Record<string, string> = {
    conflict: 'That Telegram account is already linked to a different Backlog.fyi account.',
    exists: 'You already have a Telegram account linked.',
    failed: 'Could not link Telegram. Please try again.',
    unavailable: 'Telegram linking is temporarily unavailable.',
};

function readLinkNotice(): string | null {
    const code = new URLSearchParams(window.location.search).get('telegramLink');
    return code && code !== 'success' ? LINK_NOTICES[code] ?? LINK_NOTICES.failed : null;
}

/**
 * "Connected accounts" — the login methods linked to the current user. Lets the user link a
 * Telegram account (via the OIDC redirect flow) and unlink any method, guarded so they can't
 * remove their last way to sign in. Email is linked through the verify-email field above; it
 * appears here once verified. Provider-agnostic so a future Google method just shows up as a row.
 */
export function ConnectedAccounts() {
    const { setUser } = useAuth();
    const [identities, setIdentities] = useState<LinkedIdentity[] | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(() => readLinkNotice());

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
                setError('You can’t remove your only sign-in method. Link another first.');
            } else if (!(e instanceof ApiError && e.status === 401)) {
                setError('Could not unlink. Please try again.');
            }
        } finally {
            setBusy(false);
        }
    };

    const canUnlink = (identities?.length ?? 0) > 1;

    return (
        <div className="field">
            <label className="field__label">Connected accounts</label>
            <p className="settings-hint">Ways you can sign in. Keep at least one.</p>

            <ul className="connected-accounts">
                {identities?.map(identity => (
                    <li key={identity.provider} className="connected-account">
                        <span className="connected-account__name">
                            {PROVIDER_LABELS[identity.provider] ?? identity.provider}
                        </span>
                        <button
                            type="button"
                            className="btn btn--ghost connected-account__action"
                            onClick={() => handleUnlink(identity.provider)}
                            disabled={busy || !canUnlink}
                            title={canUnlink ? undefined : 'Link another method before removing this one'}
                        >
                            Unlink
                        </button>
                    </li>
                ))}
                {identities?.length === 0 && (
                    <li className="settings-hint">No login methods linked yet.</li>
                )}
            </ul>

            {!hasTelegram && (
                <a className="btn btn--ghost" href={TELEGRAM_LINK_URL}>
                    Link Telegram
                </a>
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
    fetchIdentities,
    fetchMe,
    linkTelegram,
    unlinkIdentity,
    type LinkedIdentity,
    type TelegramWidgetPayload,
} from '../auth/authApi';
import { ApiError } from '../api';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME as string | undefined;
const LINK_CALLBACK = 'onTaskerTelegramLink';

declare global {
    interface Window {
        [LINK_CALLBACK]?: (user: TelegramWidgetPayload) => void;
    }
}

const PROVIDER_LABELS: Record<string, string> = {
    telegram: 'Telegram',
    email: 'Email',
    google: 'Google',
};

/**
 * "Connected accounts" — the login methods linked to the current user. Lets the user link a
 * Telegram account (via the official widget) and unlink any method, guarded so they can't remove
 * their last way to sign in. Email is linked through the verify-email field above; it appears here
 * once verified. Provider-agnostic so a future Google method just shows up as another row.
 */
export function ConnectedAccounts() {
    const { setUser } = useAuth();
    const [identities, setIdentities] = useState<LinkedIdentity[] | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [linking, setLinking] = useState(false);
    const telegramSlot = useRef<HTMLDivElement | null>(null);

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

    const hasTelegram = identities?.some(i => i.provider === 'telegram') ?? false;

    // Mount the Telegram widget when the user opens the link affordance.
    useEffect(() => {
        if (!linking || !BOT_USERNAME || !telegramSlot.current) return;
        window[LINK_CALLBACK] = async (payload) => {
            setBusy(true);
            setError(null);
            try {
                const user = await linkTelegram(payload);
                setUser(user);
                await refresh();
                setLinking(false);
            } catch (e) {
                if (e instanceof ApiError && e.status === 409) {
                    setError('That Telegram account is already linked to a different Backlog.fyi account.');
                } else {
                    setError('Could not link Telegram. Please try again.');
                }
            } finally {
                setBusy(false);
            }
        };
        const container = telegramSlot.current;
        const script = document.createElement('script');
        script.src = 'https://telegram.org/js/telegram-widget.js?22';
        script.async = true;
        script.setAttribute('data-telegram-login', BOT_USERNAME);
        script.setAttribute('data-size', 'medium');
        script.setAttribute('data-radius', '14');
        script.setAttribute('data-onauth', `${LINK_CALLBACK}(user)`);
        script.setAttribute('data-request-access', 'write');
        container.appendChild(script);
        return () => {
            container.replaceChildren();
            window[LINK_CALLBACK] = undefined;
        };
    }, [linking, refresh, setUser]);

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

            {!hasTelegram && BOT_USERNAME && (
                linking ? (
                    <div ref={telegramSlot} className="connected-account__telegram-slot" />
                ) : (
                    <button
                        type="button"
                        className="btn btn--ghost"
                        onClick={() => { setError(null); setLinking(true); }}
                        disabled={busy}
                    >
                        Link Telegram
                    </button>
                )
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

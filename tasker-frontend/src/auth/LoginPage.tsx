import { useEffect, useRef, useState, useCallback } from 'react';
import { useAuth } from './AuthContext';
import { demoLogin, devLogin, telegramLogin, type TelegramWidgetPayload } from './authApi';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME as string | undefined;
const TELEGRAM_CALLBACK = 'onTaskerTelegramAuth';

declare global {
    interface Window {
        [TELEGRAM_CALLBACK]?: (user: TelegramWidgetPayload) => void;
    }
}

type PolicyKey = 'tos' | 'pp';

const POLICY_CONTENT: Record<PolicyKey, { title: string; body: string }> = {
    tos: {
        title: 'Terms of Service',
        body: `Last updated: April 2026 — placeholder, full terms coming soon.

Backlog.fyi is a personal productivity tool. By using the service you agree to:
• Use the service only for lawful purposes.
• Not attempt to interfere with the service or its infrastructure.
• Accept that the service is provided as-is, without warranties of any kind.

We reserve the right to suspend or terminate accounts that abuse the service or violate these terms. We may update these terms at any time; continued use constitutes acceptance.

Questions? Reach us at hello@backlog.fyi.`,
    },
    pp: {
        title: 'Privacy Policy',
        body: `Last updated: April 2026 — placeholder, full policy coming soon.

What we collect
When you sign in with Telegram, we receive the profile information Telegram provides: your numeric user ID, display name, username (if set), and profile photo URL. We store only what is necessary to identify your account.

How we use it
Your data is used solely to operate the service — to associate your tasks with your account. We do not sell, rent, or share your personal information with third parties.

Data retention
Your data is retained as long as your account exists. You may request deletion at any time by contacting hello@backlog.fyi.

Cookies
We use a single session cookie (SESSION) to keep you logged in. No third-party tracking cookies are set.

Questions? Reach us at hello@backlog.fyi.`,
    },
};

export function LoginPage() {
    const { setUser } = useAuth();
    const widgetContainer = useRef<HTMLDivElement | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [policy, setPolicy] = useState<PolicyKey | null>(null);
    const closePolicy = useCallback(() => setPolicy(null), []);

    useEffect(() => {
        window[TELEGRAM_CALLBACK] = async (payload) => {
            setError(null);
            setBusy(true);
            try {
                const user = await telegramLogin(payload);
                setUser(user);
            } catch (e) {
                console.error('Telegram login failed', e);
                setError('Telegram login failed. Please try again.');
            } finally {
                setBusy(false);
            }
        };
        return () => {
            window[TELEGRAM_CALLBACK] = undefined;
        };
    }, [setUser]);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') closePolicy(); };
        if (policy) window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [policy, closePolicy]);

    useEffect(() => {
        if (!BOT_USERNAME || !widgetContainer.current) return;
        const container = widgetContainer.current;
        const script = document.createElement('script');
        script.src = 'https://telegram.org/js/telegram-widget.js?22';
        script.async = true;
        script.setAttribute('data-telegram-login', BOT_USERNAME);
        script.setAttribute('data-size', 'large');
        script.setAttribute('data-onauth', `${TELEGRAM_CALLBACK}(user)`);
        script.setAttribute('data-request-access', 'write');
        container.appendChild(script);
        return () => {
            container.replaceChildren();
        };
    }, []);

    const handleDemoLogin = async () => {
        setError(null);
        setBusy(true);
        try {
            const user = await demoLogin();
            setUser(user);
        } catch (e) {
            console.error('Demo login failed', e);
            setError('Demo mode is currently unavailable. Please try again.');
        } finally {
            setBusy(false);
        }
    };

    const handleDevLogin = async () => {
        setError(null);
        setBusy(true);
        try {
            const user = await devLogin();
            setUser(user);
        } catch (e) {
            console.error('Dev login failed', e);
            setError('Dev login failed. Is the backend running with the dev profile?');
        } finally {
            setBusy(false);
        }
    };

    const openPolicy = policy ? POLICY_CONTENT[policy] : null;

    return (
        <>
            <div className="board-wrap">
                <div className="board board--empty" style={{ flexDirection: 'column', gap: 24 }}>
                    <div style={{ textAlign: 'center' }}>
                        <h1 style={{ margin: 0, fontSize: 32 }}>Backlog.fyi</h1>
                        <p style={{ marginTop: 8, color: '#555' }}>
                            Pin up your tasks. Let the assistant schedule your week.
                        </p>
                    </div>
                    {BOT_USERNAME ? (
                        <div ref={widgetContainer} />
                    ) : (
                        <p style={{ color: '#a86000', maxWidth: 400, textAlign: 'center' }}>
                            Telegram bot username not configured (set <code>VITE_TELEGRAM_BOT_USERNAME</code>).
                        </p>
                    )}
                    <button
                        type="button"
                        onClick={handleDemoLogin}
                        disabled={busy}
                        style={{
                            padding: '10px 20px',
                            fontSize: 14,
                            border: '1px dashed #aaa',
                            background: '#f5f5f5',
                            cursor: busy ? 'wait' : 'pointer',
                            borderRadius: 6,
                            color: '#555',
                        }}
                    >
                        Try demo (no account needed)
                    </button>
                    {import.meta.env.DEV && (
                        <button
                            type="button"
                            onClick={handleDevLogin}
                            disabled={busy}
                            style={{
                                padding: '10px 20px',
                                fontSize: 14,
                                border: '1px dashed #888',
                                background: '#fafafa',
                                cursor: busy ? 'wait' : 'pointer',
                                borderRadius: 6,
                            }}
                        >
                            Dev login (skip Telegram)
                        </button>
                    )}
                    {error && <p style={{ color: '#c83218' }}>{error}</p>}
                    <p style={{ margin: 0, fontSize: 12, color: '#888', textAlign: 'center' }}>
                        By continuing you agree to our{' '}
                        <button type="button" className="link-btn" onClick={() => setPolicy('tos')}>Terms of Service</button>
                        {' '}and{' '}
                        <button type="button" className="link-btn" onClick={() => setPolicy('pp')}>Privacy Policy</button>.
                    </p>
                </div>
            </div>

            {/* Policy modal */}
            <div
                className={`modal-overlay${policy ? ' modal-overlay--open' : ''}`}
                onClick={(e) => { if (e.target === e.currentTarget) closePolicy(); }}
            >
                <div className="modal" role="dialog" aria-modal="true" aria-labelledby="policy-title">
                    <div className="modal__header">
                        <span id="policy-title" className="modal__title">{openPolicy?.title}</span>
                        <button type="button" className="drawer__close" onClick={closePolicy} aria-label="Close">✕</button>
                    </div>
                    <div className="modal__body">
                        <pre style={{ margin: 0, fontFamily: 'inherit', fontSize: 14, lineHeight: 1.65, whiteSpace: 'pre-wrap', color: '#444' }}>
                            {openPolicy?.body}
                        </pre>
                    </div>
                </div>
            </div>
        </>
    );
}

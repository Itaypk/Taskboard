import { useEffect, useRef, useState, useCallback } from 'react';
import { useAuth } from './AuthContext';
import { demoLogin, devLogin, telegramLogin, type TelegramWidgetPayload } from './authApi';
import MarkdownRenderer from '../components/MarkdownRenderer';
import tosContent from './tos.md?raw';
import ppContent from './privacy-policy.md?raw';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME as string | undefined;
const TELEGRAM_CALLBACK = 'onTaskerTelegramAuth';

declare global {
    interface Window {
        [TELEGRAM_CALLBACK]?: (user: TelegramWidgetPayload) => void;
    }
}

type PolicyKey = 'tos' | 'pp';

const POLICY_CONTENT: Record<PolicyKey, { title: string; body: string }> = {
    tos: { title: 'Terms of Service', body: tosContent },
    pp:  { title: 'Privacy Policy',   body: ppContent  },
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
                        <MarkdownRenderer content={openPolicy?.body ?? ''} />
                    </div>
                </div>
            </div>
        </>
    );
}

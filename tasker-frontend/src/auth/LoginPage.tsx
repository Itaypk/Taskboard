import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { demoLogin, devLogin, telegramLogin, type TelegramWidgetPayload } from './authApi';
import styles from './LoginPage.module.css';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME as string | undefined;
const TELEGRAM_CALLBACK = 'onTaskerTelegramAuth';

declare global {
    interface Window {
        [TELEGRAM_CALLBACK]?: (user: TelegramWidgetPayload) => void;
    }
}

export function LoginPage() {
    const { setUser } = useAuth();
    const widgetContainer = useRef<HTMLDivElement | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

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

    return (
        <main className="board-wrap">
            <div className={styles.landing}>
                <header className={styles.hero}>
                    <h1 className={styles.title}>Backlog.fyi</h1>
                    <p className={styles.tagline}>
                        Pin up your tasks. Let the assistant schedule your week.
                    </p>
                </header>

                <section className={styles.pitch}>
                    <p>
                        Backlog.fyi is a hobby AI weekly planner. Capture tasks on a digital cork
                        board as you think of them. Each week, an assistant on Telegram reads your
                        Google Calendar, asks what you want to get done, and writes the agreed
                        tasks back as time blocks.
                    </p>
                </section>

                <section aria-labelledby="how-it-works">
                    <h2 id="how-it-works" className={styles.sectionTitle}>How it works</h2>
                    <ol className={styles.steps}>
                        <li><strong>Pin tasks</strong> to the board whenever they come up.</li>
                        <li><strong>Plan on Telegram</strong> in a short weekly chat with the assistant.</li>
                        <li><strong>Calendar fills itself</strong> — agreed tasks become time blocks on Google Calendar.</li>
                    </ol>
                </section>

                <section className={styles.cta} aria-labelledby="get-started">
                    <h2 id="get-started" className={styles.sectionTitle}>Try it</h2>
                    <button
                        type="button"
                        onClick={handleDemoLogin}
                        disabled={busy}
                        className={styles.demoBtn}
                    >
                        Try the demo — no account needed
                    </button>
                    <p className={styles.demoHint}>
                        Loads a sandboxed account so you can play with the board.
                    </p>

                    <div className={styles.divider}><span>or sign in with Telegram</span></div>

                    {BOT_USERNAME ? (
                        <div ref={widgetContainer} className={styles.telegram} />
                    ) : (
                        <p className={styles.warning}>
                            Telegram bot username not configured (set <code>VITE_TELEGRAM_BOT_USERNAME</code>).
                        </p>
                    )}

                    {import.meta.env.DEV && (
                        <button
                            type="button"
                            onClick={handleDevLogin}
                            disabled={busy}
                            className={styles.devBtn}
                        >
                            Dev login (skip Telegram)
                        </button>
                    )}

                    {error && <p className={styles.error}>{error}</p>}
                </section>

                <p className={styles.status} role="note">
                    <strong>Status:</strong> the backlog is live; the Telegram weekly planning
                    conversation and Google Calendar integration are in active development.
                </p>

                <footer className={styles.footer}>
                    By continuing you agree to our{' '}
                    <Link to="/terms">Terms of Service</Link>{' '}and{' '}
                    <Link to="/privacy">Privacy Policy</Link>.
                </footer>
            </div>
        </main>
    );
}

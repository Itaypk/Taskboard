import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { demoLogin, devLogin, requestEmailLogin, telegramLoginUrl } from './authApi';
import pineappleUrl from '../assets/pineapple.webp';
import styles from './LoginPage.module.css';

// The Telegram OIDC callback redirects back here with a notice if login didn't complete.
const TELEGRAM_NOTICES: Record<string, string> = {
    failed: 'Telegram sign-in didn’t complete. Please try again.',
    unavailable: 'Telegram sign-in is temporarily unavailable. Try another method.',
};

function readTelegramNotice(): string | null {
    const code = new URLSearchParams(window.location.search).get('telegramLogin');
    return code ? TELEGRAM_NOTICES[code] ?? TELEGRAM_NOTICES.failed : null;
}

export function LoginPage({ next }: { next?: string } = {}) {
    const { setUser } = useAuth();
    // A failed Telegram redirect lands on "/" with the modal closed — auto-open it to show the error.
    const initialNotice = readTelegramNotice();
    const [modalOpen, setModalOpen] = useState(initialNotice != null);
    const [error, setError] = useState<string | null>(initialNotice);
    const [busy, setBusy] = useState(false);

    // Strip the notice from the URL so a refresh doesn't re-show it.
    useEffect(() => {
        if (initialNotice) window.history.replaceState(null, '', window.location.pathname);
    }, [initialNotice]);

    // Close the modal on Escape.
    useEffect(() => {
        if (!modalOpen) return;
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setModalOpen(false); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [modalOpen]);

    const handleDemoLogin = async () => {
        setError(null);
        setBusy(true);
        try {
            const user = await demoLogin();
            setUser(user);
        } catch (e) {
            console.error('Start-now login failed', e);
            setError("Couldn't start a new account. Please try again.");
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
        <div className={styles.page}>
            <header className={styles.nav}>
                <div className={styles.brand}>
                    <span className={styles.logoWrap}>
                        <span className="logo-tape">Backlog.fyi</span>
                        <span className={styles.beta}>beta</span>
                    </span>
                    <span className={styles.copyright}>© 2026</span>
                </div>
                <nav className={styles.navRight}>
                    <Link to="/terms" className={styles.navLink}>Terms</Link>
                    <Link to="/privacy" className={styles.navLink}>Privacy</Link>
                    <button type="button" className={styles.navLink} onClick={() => setModalOpen(true)}>
                        Log in
                    </button>
                </nav>
            </header>

            <div className={styles.stage}>
                <div className={styles.heroWrap}>
                    <div className={styles.heroSticky}>
                        <span className={styles.tape} style={{ top: -11, left: 70, transform: 'rotate(-7deg)' }} />
                        <span className={styles.tape} style={{ top: -11, right: 90, width: 90, transform: 'rotate(6deg)' }} />

                        <div className={styles.heroTag}>This week · top of mind</div>
                        <h1 className={styles.heroH1}>
                            Tasks you keep<br />
                            <em className={styles.heroEm}>actually doing.</em>
                        </h1>
                        <p className={styles.heroSub}>
                            Capture what's on your plate. Talk through a realistic week with the
                            assistant. Agreed tasks land on your calendar as time blocks.
                        </p>

                        <div className={styles.ctaRow}>
                            <button
                                type="button"
                                className={styles.getStarted}
                                onClick={() => setModalOpen(true)}
                                disabled={busy}
                            >
                                Get started — it's free
                            </button>
                            <button
                                type="button"
                                className={styles.sandboxLink}
                                onClick={handleDemoLogin}
                                disabled={busy}
                            >
                                or start now, no sign-up →
                            </button>
                        </div>
                    </div>
                </div>

                <div className={styles.satGrid}>
                    <div className={`${styles.sat} ${styles.satPink}`}>
                        <div className={styles.satTag}>The problem</div>
                        <h3 className={styles.satH}>Tasks get written down — then quietly buried.</h3>
                    </div>

                    <div className={`${styles.sat} ${styles.satPeach}`}>
                        <span className={styles.tape} style={{ top: -9, left: 26, transform: 'rotate(-4deg)' }} />
                        <div className={styles.satTag}>The payoff</div>
                        <h3 className={styles.satH}>A week you can actually commit to.</h3>
                        <div className={styles.satMeta}>Not another list to ignore.</div>
                    </div>

                    <div className={`${styles.sat} ${styles.satBlue}`}>
                        <div className={styles.satTag}>The method</div>
                        <h3 className={styles.satH}>Time-blocking — but you don't do the planning.</h3>
                        <div className={styles.satMeta}>The assistant proposes; you push back.</div>
                    </div>

                    <div className={`${styles.sat} ${styles.satMint}`}>
                        <span className={styles.tape} style={{ top: -9, right: 30, transform: 'rotate(5deg)' }} />
                        <div className={styles.satTag}>Yours alone</div>
                        <h3 className={styles.satH}>No ads. No trackers. Encrypted at rest. ZDR&nbsp;AI.</h3>
                    </div>
                </div>
            </div>

            <img className="pineapple-pet" src={pineappleUrl} alt="" aria-hidden="true" />

            {modalOpen && (
                <LoginModal
                    busy={busy}
                    error={error}
                    next={next}
                    onSandbox={handleDemoLogin}
                    onDevLogin={handleDevLogin}
                    onClose={() => setModalOpen(false)}
                />
            )}
        </div>
    );
}

function LoginModal({
    busy,
    error,
    next,
    onSandbox,
    onDevLogin,
    onClose,
}: {
    busy: boolean;
    error: string | null;
    next?: string;
    onSandbox: () => void;
    onDevLogin: () => void;
    onClose: () => void;
}) {
    const [emailMode, setEmailMode] = useState(false);
    const [email, setEmail] = useState('');
    const [emailSent, setEmailSent] = useState(false);
    const [emailBusy, setEmailBusy] = useState(false);
    const [emailErr, setEmailErr] = useState<string | null>(null);

    const submitEmail = async (e: FormEvent) => {
        e.preventDefault();
        const trimmed = email.trim();
        if (!trimmed) return;
        setEmailBusy(true);
        setEmailErr(null);
        try {
            await requestEmailLogin(trimmed, next);
            setEmailSent(true);
        } catch (err) {
            console.error('Email login request failed', err);
            setEmailErr('Could not send the sign-in link. Please try again.');
        } finally {
            setEmailBusy(false);
        }
    };

    return (
        <div className={styles.overlay} onClick={onClose}>
            <div className={styles.modal} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
                <button type="button" className={styles.modalClose} onClick={onClose} aria-label="Close">✕</button>

                <div className={styles.modalTag}>Welcome in</div>
                <h2 className={styles.modalH}>Pick how you'd like to continue</h2>
                <p className={styles.modalSub}>One tap. We'll create your board if it's your first time.</p>

                <div className={styles.channels}>
                    <a
                        href={telegramLoginUrl(next)}
                        className={`${styles.channelBtn} ${styles.chTelegram}`}
                    >
                        <TelegramIcon /> Log in with Telegram
                    </a>

                    <button type="button" className={`${styles.channelBtn} ${styles.chGoogle}`} disabled>
                        <GIcon /> Continue with Google
                        <span className={styles.soonBadge}>Soon</span>
                    </button>

                    {emailSent ? (
                        <p className={styles.emailSent}>
                            Check your inbox — we sent a sign-in link to <strong>{email.trim()}</strong>.
                            It expires in 30 minutes.
                        </p>
                    ) : emailMode ? (
                        <form className={styles.emailForm} onSubmit={submitEmail}>
                            <input
                                type="email"
                                className={styles.emailInput}
                                placeholder="you@example.com"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                autoFocus
                                required
                            />
                            <button
                                type="submit"
                                className={`${styles.channelBtn} ${styles.chEmail}`}
                                disabled={emailBusy}
                            >
                                {emailBusy ? 'Sending…' : 'Send sign-in link'}
                            </button>
                            {emailErr && <p className={styles.error}>{emailErr}</p>}
                        </form>
                    ) : (
                        <button
                            type="button"
                            className={`${styles.channelBtn} ${styles.chEmail}`}
                            onClick={() => setEmailMode(true)}
                        >
                            <MailIcon /> Continue with email
                        </button>
                    )}
                </div>

                <p className={styles.moreNote}>More ways to sign in are on the way</p>

                <div className={styles.divider}>
                    <span className={styles.divLine} />
                    <span className={styles.divText}>just looking?</span>
                    <span className={styles.divLine} />
                </div>

                <button type="button" className={styles.sandboxCard} onClick={onSandbox} disabled={busy}>
                    Start now — no sign-up →
                    <span className={styles.sandboxSub}>Get a real board instantly. Add an email or Telegram later to keep it.</span>
                </button>

                <p className={styles.fine}>
                    By continuing you agree to our <Link to="/terms">Terms</Link> and{' '}
                    <Link to="/privacy">Privacy Policy</Link>. No ads, no trackers — ever.
                </p>

                {import.meta.env.DEV && (
                    <button type="button" className={styles.devBtn} onClick={onDevLogin} disabled={busy}>
                        Dev login (skip Telegram)
                    </button>
                )}

                {error && <p className={styles.error}>{error}</p>}
            </div>
        </div>
    );
}

function GIcon() {
    return (
        <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 01-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8z" />
            <path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.1 0-5.8-2.1-6.7-5H1.3v3.1A12 12 0 0012 24z" />
            <path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 010-4.6V6.6H1.3a12 12 0 000 10.8l4-3.1z" />
            <path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8l3.4-3.4A12 12 0 0012 0 12 12 0 001.3 6.6l4 3.1c.9-2.9 3.6-5 6.7-5z" />
        </svg>
    );
}

function TelegramIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="12" fill="#2aabee" />
            <path
                fill="#fff"
                d="M5.5 11.9l11-4.25c.51-.18.96.12.79.9l-1.87 8.82c-.13.62-.5.77-1.02.48l-2.82-2.08-1.36 1.31c-.15.15-.28.28-.57.28l.2-2.88 5.25-4.74c.23-.2-.05-.32-.35-.12l-6.49 4.08-2.8-.87c-.6-.19-.62-.6.13-.89z"
            />
        </svg>
    );
}

function MailIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <rect x="3" y="5" width="18" height="14" rx="2.5" />
            <path d="M4 7l8 6 8-6" />
        </svg>
    );
}

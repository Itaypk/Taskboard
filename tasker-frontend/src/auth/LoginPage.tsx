import { useEffect, useState, type FormEvent } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ApiError } from '../api';
import { useAuth } from './AuthContext';
import { demoLogin, devLogin, requestEmailLogin, telegramLoginUrl } from './authApi';
import pineappleUrl from '../assets/pineapple.webp';
import styles from './LoginPage.module.css';
import { Arrow } from '../components/Arrow';

// The Telegram OIDC callback redirects back here with a notice code if login didn't complete;
// `unavailable` maps to its own copy, every other value degrades to the generic "failed" message.
function readTelegramNoticeKey(): string | null {
    const code = new URLSearchParams(window.location.search).get('telegramLogin');
    if (!code) return null;
    return code === 'unavailable' ? 'login.notices.telegramUnavailable' : 'login.notices.telegramFailed';
}

// The sandbox is the primary call-to-action, so its two expected failures deserve to say what
// actually happened: 429 is the per-IP throttle (everyone behind one office/carrier gateway shares
// it), 503 is the unclaimed-account cap. Anything else stays generic.
function demoErrorKey(e: unknown): string {
    if (e instanceof ApiError && e.status === 429) return 'login.errors.demoRateLimited';
    if (e instanceof ApiError && e.status === 503) return 'login.errors.demoAtCapacity';
    return 'login.errors.demoFailed';
}

export function LoginPage({ next }: { next?: string } = {}) {
    const { t } = useTranslation();
    const { setUser } = useAuth();
    // A failed Telegram redirect lands on "/" with the modal closed — auto-open it to show the error.
    const initialNoticeKey = readTelegramNoticeKey();
    const initialNotice = initialNoticeKey ? t(initialNoticeKey) : null;
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
            setError(t(demoErrorKey(e)));
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
            setError(t('login.errors.devFailed'));
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
                    <button type="button" className={styles.navLink} onClick={() => setModalOpen(true)}>
                        {t('login.nav.logIn')}
                    </button>
                </nav>
            </header>

            <div className={styles.stage}>
                <div className={styles.heroWrap}>
                    <div className={styles.heroSticky}>
                        <span className={styles.tape} style={{ top: -11, left: 70, transform: 'rotate(-7deg)' }} />
                        <span className={styles.tape} style={{ top: -11, right: 90, width: 90, transform: 'rotate(6deg)' }} />

                        <div className={styles.heroTag}>{t('login.hero.tag')}</div>
                        <h1 className={styles.heroH1}>
                            {t('login.hero.titleLine1')}<br />
                            <em className={styles.heroEm}>{t('login.hero.titleLine2')}</em>
                        </h1>
                        <p className={styles.heroSub}>
                            {t('login.hero.sub')}
                        </p>

                        {/* The sandbox leads: nobody signs in to a product they haven't seen, and an
                            unclaimed account becomes a full one just by linking email or Telegram. */}
                        <div className={styles.ctaRow}>
                            <button
                                type="button"
                                className={styles.getStarted}
                                onClick={handleDemoLogin}
                                disabled={busy}
                            >
                                {t('login.hero.startNow')} <Arrow direction="forward" />
                            </button>
                            <button
                                type="button"
                                className={styles.sandboxLink}
                                onClick={() => setModalOpen(true)}
                                disabled={busy}
                            >
                                {t('login.hero.signIn')}
                            </button>
                        </div>
                    </div>
                </div>

                <div className={styles.satGrid}>
                    <div className={`${styles.sat} ${styles.satPink}`}>
                        <div className={styles.satTag}>{t('login.satellites.problemTag')}</div>
                        <h3 className={styles.satH}>{t('login.satellites.problemTitle')}</h3>
                    </div>

                    <div className={`${styles.sat} ${styles.satPeach}`}>
                        <span className={styles.tape} style={{ top: -9, left: 26, transform: 'rotate(-4deg)' }} />
                        <div className={styles.satTag}>{t('login.satellites.payoffTag')}</div>
                        <h3 className={styles.satH}>{t('login.satellites.payoffTitle')}</h3>
                        <div className={styles.satMeta}>{t('login.satellites.payoffMeta')}</div>
                    </div>

                    <div className={`${styles.sat} ${styles.satBlue}`}>
                        <div className={styles.satTag}>{t('login.satellites.methodTag')}</div>
                        <h3 className={styles.satH}>{t('login.satellites.methodTitle')}</h3>
                        <div className={styles.satMeta}>{t('login.satellites.methodMeta')}</div>
                    </div>

                    <div className={`${styles.sat} ${styles.satMint}`}>
                        <span className={styles.tape} style={{ top: -9, right: 30, transform: 'rotate(5deg)' }} />
                        <div className={styles.satTag}>{t('login.satellites.privacyTag')}</div>
                        <h3 className={styles.satH}>{t('login.satellites.privacyTitle')}</h3>
                    </div>
                </div>
            </div>

            <img className="pineapple-pet" src={pineappleUrl} alt="" aria-hidden="true" decoding="async" fetchPriority="low" />

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
    const { t } = useTranslation();
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
            // A blocked (disposable) domain is the one failure the user can act on, so name it.
            const blocked = err instanceof ApiError && err.code === 'BLOCKED_EMAIL_DOMAIN';
            setEmailErr(t(blocked ? 'login.errors.blockedEmailDomain' : 'login.errors.emailSendFailed'));
        } finally {
            setEmailBusy(false);
        }
    };

    return (
        <div className={styles.overlay} onClick={onClose}>
            <div className={styles.modal} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
                <button type="button" className={styles.modalClose} onClick={onClose} aria-label={t('login.modal.close')}>✕</button>

                <div className={styles.modalTag}>{t('login.modal.tag')}</div>
                <h2 className={styles.modalH}>{t('login.modal.title')}</h2>
                <p className={styles.modalSub}>{t('login.modal.sub')}</p>

                <div className={styles.channels}>
                    <a
                        href={telegramLoginUrl(next)}
                        className={`${styles.channelBtn} ${styles.chTelegram}`}
                    >
                        <TelegramIcon /> {t('login.modal.telegram')}
                    </a>

                    {/* Google login isn't built yet — hide the disabled entry until it is. */}
                    {/* <button type="button" className={`${styles.channelBtn} ${styles.chGoogle}`} disabled>
                        <GIcon /> {t('login.modal.google')}
                        <span className={styles.soonBadge}>{t('login.modal.soon')}</span>
                    </button> */}

                    {emailSent ? (
                        <p className={styles.emailSent}>
                            <Trans
                                i18nKey="login.modal.emailSent"
                                values={{ email: email.trim() }}
                                components={{ strong: <strong /> }}
                            />
                        </p>
                    ) : emailMode ? (
                        <form className={styles.emailForm} onSubmit={submitEmail}>
                            <input
                                type="email"
                                className={styles.emailInput}
                                placeholder={t('login.modal.emailPlaceholder')}
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
                                {emailBusy ? t('login.modal.sending') : t('login.modal.sendLink')}
                            </button>
                            {emailErr && <p className={styles.error}>{emailErr}</p>}
                        </form>
                    ) : (
                        <button
                            type="button"
                            className={`${styles.channelBtn} ${styles.chEmail}`}
                            onClick={() => setEmailMode(true)}
                        >
                            <MailIcon /> {t('login.modal.continueEmail')}
                        </button>
                    )}
                </div>

                {/* <p className={styles.moreNote}>{t('login.modal.moreWays')}</p> */}

                <div className={styles.divider}>
                    <span className={styles.divLine} />
                    <span className={styles.divText}>{t('login.modal.justLooking')}</span>
                    <span className={styles.divLine} />
                </div>

                <button type="button" className={styles.sandboxCard} onClick={onSandbox} disabled={busy}>
                    {t('login.modal.sandbox')} <Arrow direction="forward" />
                    <span className={styles.sandboxSub}>{t('login.modal.sandboxSub')}</span>
                </button>

                <p className={styles.fine}>
                    <Trans
                        i18nKey="login.modal.fine"
                        components={{ terms: <Link to="/terms" />, privacy: <Link to="/privacy" /> }}
                    />
                </p>

                {import.meta.env.DEV && (
                    <button type="button" className={styles.devBtn} onClick={onDevLogin} disabled={busy}>
                        {t('login.modal.devLogin')}
                    </button>
                )}

                {error && <p className={styles.error}>{error}</p>}
            </div>
        </div>
    );
}

// Unused while the Google login button above is commented out; kept for when it ships.
// function GIcon() {
//     return (
//         <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
//             <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 01-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8z" />
//             <path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.1 0-5.8-2.1-6.7-5H1.3v3.1A12 12 0 0012 24z" />
//             <path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 010-4.6V6.6H1.3a12 12 0 000 10.8l4-3.1z" />
//             <path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8l3.4-3.4A12 12 0 0012 0 12 12 0 001.3 6.6l4 3.1c.9-2.9 3.6-5 6.7-5z" />
//         </svg>
//     );
// }

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

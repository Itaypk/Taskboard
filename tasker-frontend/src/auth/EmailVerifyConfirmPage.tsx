import { useState } from 'react';
import { Link } from 'react-router-dom';
import { confirmEmailVerification } from './authApi';
import styles from './EmailLoginConfirmPage.module.css';

type PageState =
    | { phase: 'ready' }
    | { phase: 'confirming' }
    | { phase: 'success' }
    | { phase: 'error'; message: string };

export function EmailVerifyConfirmPage() {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');

    const [state, setState] = useState<PageState>(
        token ? { phase: 'ready' } : { phase: 'error', message: 'This verification link is invalid or has expired.' },
    );

    const handleVerify = async () => {
        if (!token) return;
        setState({ phase: 'confirming' });
        try {
            const { success } = await confirmEmailVerification(token);
            if (success) {
                setState({ phase: 'success' });
            } else {
                setState({ phase: 'error', message: 'This verification link is invalid or has expired.' });
            }
        } catch {
            setState({ phase: 'error', message: 'Something went wrong. Please try again.' });
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
            </header>

            <div className={styles.stage}>
                {state.phase === 'success' ? (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>All done</div>
                        <h1 className={styles.cardH}>Email verified</h1>
                        <p className={styles.cardBody}>
                            Your email address has been confirmed. You can now use it to sign in
                            with a magic link.
                        </p>
                        <Link to="/" className={styles.signInBtn} style={{ textDecoration: 'none', textAlign: 'center' }}>
                            Go to my board →
                        </Link>
                    </div>
                ) : (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>Verify email</div>
                        <h1 className={styles.cardH}>Confirm your address</h1>
                        <p className={styles.cardBody}>
                            Click the button below to verify your email address. This links it to
                            your account so you can use it to sign in.
                        </p>

                        <button
                            type="button"
                            className={styles.signInBtn}
                            onClick={handleVerify}
                            disabled={state.phase === 'confirming' || state.phase === 'error'}
                        >
                            {state.phase === 'confirming' ? (
                                <span className={styles.spinner} aria-label="Verifying…" />
                            ) : (
                                'Verify my email'
                            )}
                        </button>

                        {state.phase === 'error' && (
                            <p className={styles.errorMsg} role="alert">
                                {state.message}
                            </p>
                        )}

                        <Link to="/" className={styles.backLink}>
                            ← Back to app
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { precheckEmailLogin, completeEmailLogin } from './authApi';
import layout from './LoginPage.module.css';
import styles from './EmailLoginConfirmPage.module.css';

type PageState =
    | { phase: 'loading' }
    | { phase: 'invalid' }
    | { phase: 'ready' }
    | { phase: 'confirming' }
    | { phase: 'error'; message: string };

function localRedirect(path: string | null | undefined): string {
    if (path && path.startsWith('/') && !path.startsWith('//') && !path.includes('\\')) {
        return path;
    }
    return '/';
}

export function EmailLoginConfirmPage() {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    const next = params.get('next');

    const [state, setState] = useState<PageState>({ phase: 'loading' });

    useEffect(() => {
        if (!token) {
            setState({ phase: 'invalid' });
            return;
        }
        precheckEmailLogin(token)
            .then(({ valid }) => setState(valid ? { phase: 'ready' } : { phase: 'invalid' }))
            .catch(() => setState({ phase: 'invalid' }));
    }, [token]);

    const handleSignIn = async () => {
        if (!token) return;
        setState({ phase: 'confirming' });
        try {
            const { outcome } = await completeEmailLogin(token);
            if (outcome === 'success') {
                window.location.replace(localRedirect(next));
            } else if (outcome === 'unverified') {
                setState({
                    phase: 'error',
                    message:
                        'That email is already linked to an unverified account. Sign in with Telegram and verify your email under Settings.',
                });
            } else {
                setState({ phase: 'error', message: 'This sign-in link is invalid or has expired.' });
            }
        } catch {
            setState({ phase: 'error', message: 'Something went wrong. Please try again.' });
        }
    };

    return (
        <div className={layout.page}>
            <header className={layout.nav}>
                <div className={layout.brand}>
                    <span className={layout.logoWrap}>
                        <span className="logo-tape">Backlog.fyi</span>
                        <span className={layout.beta}>beta</span>
                    </span>
                    <span className={layout.copyright}>© 2026</span>
                </div>
            </header>

            <div className={layout.stage}>
                {state.phase === 'loading' && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.dots} aria-label="Loading…">
                            <span /><span /><span />
                        </div>
                    </div>
                )}

                {state.phase === 'invalid' && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>Sign-in link</div>
                        <h1 className={styles.cardH}>Link expired</h1>
                        <p className={styles.cardBody}>
                            This sign-in link is invalid or has expired. Magic links are
                            single-use and expire after 30&nbsp;minutes.
                        </p>
                        <Link to="/" className={styles.backLink}>
                            ← Request a new link
                        </Link>
                    </div>
                )}

                {(state.phase === 'ready' || state.phase === 'confirming' || state.phase === 'error') && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>One more step</div>
                        <h1 className={styles.cardH}>Confirm it's you</h1>
                        <p className={styles.cardBody}>
                            Click the button below to complete sign-in. This confirms that you
                            opened this link from your inbox.
                        </p>

                        <button
                            type="button"
                            className={styles.actionBtn}
                            onClick={handleSignIn}
                            disabled={state.phase === 'confirming'}
                        >
                            {state.phase === 'confirming' ? (
                                <span className={styles.spinner} aria-label="Signing in…" />
                            ) : (
                                'Sign in to Backlog.fyi'
                            )}
                        </button>

                        {state.phase === 'error' && (
                            <p className={styles.errorMsg} role="alert">
                                {state.message}
                            </p>
                        )}

                        <Link to="/" className={styles.backLink}>
                            ← Back to sign in
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}

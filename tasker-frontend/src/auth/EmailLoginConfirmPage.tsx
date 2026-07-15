import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
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
    const { t } = useTranslation();
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    const next = params.get('next');

    // Derive the no-token case at init so the effect only ever sets state from the async precheck.
    const [state, setState] = useState<PageState>(() => (token ? { phase: 'loading' } : { phase: 'invalid' }));

    useEffect(() => {
        if (!token) return;
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
                    message: t('emailLoginConfirm.errors.unverified'),
                });
            } else {
                setState({ phase: 'error', message: t('emailLoginConfirm.errors.expired') });
            }
        } catch {
            setState({ phase: 'error', message: t('emailLoginConfirm.errors.generic') });
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
                        <div className={styles.dots} aria-label={t('emailLoginConfirm.loading')}>
                            <span /><span /><span />
                        </div>
                    </div>
                )}

                {state.phase === 'invalid' && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('emailLoginConfirm.invalid.tag')}</div>
                        <h1 className={styles.cardH}>{t('emailLoginConfirm.invalid.title')}</h1>
                        <p className={styles.cardBody}>
                            {t('emailLoginConfirm.invalid.body')}
                        </p>
                        <Link to="/" className={styles.backLink}>
                            {t('emailLoginConfirm.invalid.requestNew')}
                        </Link>
                    </div>
                )}

                {(state.phase === 'ready' || state.phase === 'confirming' || state.phase === 'error') && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('emailLoginConfirm.ready.tag')}</div>
                        <h1 className={styles.cardH}>{t('emailLoginConfirm.ready.title')}</h1>
                        <p className={styles.cardBody}>
                            {t('emailLoginConfirm.ready.body')}
                        </p>

                        <button
                            type="button"
                            className={styles.actionBtn}
                            onClick={handleSignIn}
                            disabled={state.phase === 'confirming'}
                        >
                            {state.phase === 'confirming' ? (
                                <span className={styles.spinner} aria-label={t('emailLoginConfirm.signingIn')} />
                            ) : (
                                t('emailLoginConfirm.signIn')
                            )}
                        </button>

                        {state.phase === 'error' && (
                            <p className={styles.errorMsg} role="alert">
                                {state.message}
                            </p>
                        )}

                        <Link to="/" className={styles.backLink}>
                            {t('emailLoginConfirm.backToSignIn')}
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}

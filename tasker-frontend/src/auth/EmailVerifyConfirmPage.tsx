import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { confirmEmailVerification } from './authApi';
import layout from './LoginPage.module.css';
import styles from './EmailLoginConfirmPage.module.css';

type PageState =
    | { phase: 'ready' }
    | { phase: 'confirming' }
    | { phase: 'success' }
    | { phase: 'error'; message: string };

export function EmailVerifyConfirmPage() {
    const { t } = useTranslation();
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');

    const [state, setState] = useState<PageState>(
        token ? { phase: 'ready' } : { phase: 'error', message: t('emailVerifyConfirm.errors.expired') },
    );

    const handleVerify = async () => {
        if (!token) return;
        setState({ phase: 'confirming' });
        try {
            const { success } = await confirmEmailVerification(token);
            if (success) {
                setState({ phase: 'success' });
            } else {
                setState({ phase: 'error', message: t('emailVerifyConfirm.errors.expired') });
            }
        } catch {
            setState({ phase: 'error', message: t('emailVerifyConfirm.errors.generic') });
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
                {state.phase === 'success' ? (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('emailVerifyConfirm.success.tag')}</div>
                        <h1 className={styles.cardH}>{t('emailVerifyConfirm.success.title')}</h1>
                        <p className={styles.cardBody}>
                            {t('emailVerifyConfirm.success.body')}
                        </p>
                        <Link to="/" className={styles.actionBtn}>
                            {t('emailVerifyConfirm.success.goToBoard')}
                        </Link>
                    </div>
                ) : (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('emailVerifyConfirm.ready.tag')}</div>
                        <h1 className={styles.cardH}>{t('emailVerifyConfirm.ready.title')}</h1>
                        <p className={styles.cardBody}>
                            {t('emailVerifyConfirm.ready.body')}
                        </p>

                        <button
                            type="button"
                            className={styles.actionBtn}
                            onClick={handleVerify}
                            disabled={state.phase === 'confirming' || state.phase === 'error'}
                        >
                            {state.phase === 'confirming' ? (
                                <span className={styles.spinner} aria-label={t('emailVerifyConfirm.verifying')} />
                            ) : (
                                t('emailVerifyConfirm.verifyButton')
                            )}
                        </button>

                        {state.phase === 'error' && (
                            <p className={styles.errorMsg} role="alert">
                                {state.message}
                            </p>
                        )}

                        <Link to="/" className={styles.backLink}>
                            {t('emailVerifyConfirm.backToApp')}
                        </Link>
                    </div>
                )}
            </div>
        </div>
    );
}

import { useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { LoginPage } from './LoginPage';
import { acceptInvitation, fetchInvitationPreview, type InvitationPreview } from '../api';
import layout from './LoginPage.module.css';
import styles from './EmailLoginConfirmPage.module.css';

const ACTIVE_BOARD_KEY = 'backlog.activeBoardId';

type PreviewState =
    | { phase: 'loading' }
    | { phase: 'invalid' }
    | { phase: 'ready'; preview: InvitationPreview };

/**
 * Board-invitation accept screen (`/invite?token=…`). The GET preview is side-effect-free; accepting
 * is an authenticated POST, so an unauthenticated invitee is shown the login funnel first (with a
 * `next` back here so the magic-link leg returns). The token is the authorization — whoever is
 * signed in joins as themselves (docs/BOARD-SHARING-PHASE2.md, Decision 3).
 */
export function InvitePage() {
    const { t } = useTranslation();
    const { state: auth } = useAuth();
    const token = new URLSearchParams(window.location.search).get('token');

    // Derive the no-token case at init so the effect only ever sets state from the async preview.
    const [preview, setPreview] = useState<PreviewState>(() => (token ? { phase: 'loading' } : { phase: 'invalid' }));
    const [accepting, setAccepting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!token) return;
        fetchInvitationPreview(token)
            .then(p => setPreview({ phase: 'ready', preview: p }))
            .catch(() => setPreview({ phase: 'invalid' }));
    }, [token]);

    const handleAccept = async () => {
        if (!token) return;
        setAccepting(true);
        setError(null);
        try {
            const accepted = await acceptInvitation(token);
            // Land on the board you just joined.
            localStorage.setItem(ACTIVE_BOARD_KEY, accepted.boardId);
            window.location.replace('/');
        } catch {
            setError(t('invitePage.errors.acceptFailed'));
            setAccepting(false);
        }
    };

    // Unauthenticated: send them through the normal login funnel, returning here afterwards.
    if (auth.status === 'unauthenticated') {
        return <LoginPage next={token ? `/invite?token=${encodeURIComponent(token)}` : '/invite'} />;
    }

    const signedInAs = auth.status === 'authenticated'
        ? (auth.user.telegramFirstName ?? auth.user.telegramUsername ?? auth.user.email ?? t('invitePage.yourAccount'))
        : null;

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
                {(preview.phase === 'loading' || auth.status === 'loading') && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.dots} aria-label={t('invitePage.loading')}><span /><span /><span /></div>
                    </div>
                )}

                {preview.phase === 'invalid' && auth.status !== 'loading' && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('invitePage.invalid.tag')}</div>
                        <h1 className={styles.cardH}>{t('invitePage.invalid.title')}</h1>
                        <p className={styles.cardBody}>
                            {t('invitePage.invalid.body')}
                        </p>
                        <Link to="/" className={styles.backLink}>{t('invitePage.invalid.backHome')}</Link>
                    </div>
                )}

                {preview.phase === 'ready' && auth.status === 'authenticated' && (
                    <div className={styles.card} aria-live="polite">
                        <div className={styles.cardTag}>{t('invitePage.ready.tag')}</div>
                        <h1 className={styles.cardH}>
                            {t('invitePage.ready.title', { boardName: preview.preview.boardName })}
                        </h1>
                        <p className={styles.cardBody}>
                            <Trans
                                i18nKey="invitePage.ready.body"
                                values={{ inviterName: preview.preview.inviterName, signedInAs }}
                                components={{ strong: <strong /> }}
                            />
                        </p>

                        <button
                            type="button"
                            className={styles.actionBtn}
                            onClick={handleAccept}
                            disabled={accepting}
                        >
                            {accepting ? <span className={styles.spinner} aria-label={t('invitePage.joining')} /> : t('invitePage.joinBoard')}
                        </button>

                        {error && <p className={styles.errorMsg} role="alert">{error}</p>}

                        <Link to="/" className={styles.backLink}>{t('invitePage.notNow')}</Link>
                    </div>
                )}
            </div>
        </div>
    );
}

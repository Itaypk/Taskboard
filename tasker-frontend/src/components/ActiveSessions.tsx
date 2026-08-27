import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchSessions, revokeOtherSessions, type ActiveSession } from '../auth/authApi';
import { ApiError } from '../api';
import { formatDate, formatTime } from '../i18n/format';
import styles from './ActiveSessions.module.css';

function formatMoment(iso: string): string | null {
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) return null;
    return `${formatDate(parsed)} ${formatTime(parsed, { hour: '2-digit', minute: '2-digit' })}`;
}

/**
 * "Active sessions" — where the account is currently signed in, and a way to sign out everywhere
 * else. This is the user-facing half of the long absolute session lifetime: sessions last a year,
 * so the user needs some way to end one they don't recognise without deleting their account.
 *
 * There is deliberately no per-session revoke: the backend never sends session identifiers.
 */
export function ActiveSessions() {
    const { t } = useTranslation();
    const [sessions, setSessions] = useState<ActiveSession[] | null>(null);
    const [busy, setBusy] = useState(false);
    const [confirming, setConfirming] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            setSessions(await fetchSessions());
        } catch (e) {
            console.error('Failed to load active sessions', e);
        }
    }, []);

    // Load once on mount; setState lives in the async continuation, not the effect body.
    useEffect(() => {
        let cancelled = false;
        fetchSessions()
            .then(list => { if (!cancelled) setSessions(list); })
            .catch(e => console.error('Failed to load active sessions', e));
        return () => { cancelled = true; };
    }, []);

    const handleRevoke = async () => {
        setBusy(true);
        setError(null);
        try {
            await revokeOtherSessions();
            await refresh();
            setConfirming(false);
        } catch (e) {
            // 401 already triggers a global logout via api.ts; anything else is worth showing.
            if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('activeSessions.errors.revokeFailed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const otherCount = sessions?.filter(s => !s.current).length ?? 0;

    return (
        <div className="field">
            <label className="field__label">{t('activeSessions.label')}</label>
            <p className="settings-hint">{t('activeSessions.hint')}</p>

            <ul className={styles.list}>
                {sessions?.map((session, index) => {
                    const signedIn = formatMoment(session.signedInAt);
                    const lastActive = formatMoment(session.lastActiveAt);
                    return (
                        <li key={index} className={styles.session}>
                            <span className={styles.meta}>
                                <span className={styles.device}>
                                    {session.device ?? t('activeSessions.unknownDevice')}
                                    {session.ipAddress ? ` · ${session.ipAddress}` : ''}
                                </span>
                                {lastActive && (
                                    <span className={styles.detail}>
                                        {t('activeSessions.lastActive', { when: lastActive })}
                                    </span>
                                )}
                                {signedIn && (
                                    <span className={styles.detail}>
                                        {t('activeSessions.signedIn', { when: signedIn })}
                                    </span>
                                )}
                            </span>
                            {session.current && (
                                <span className={styles.currentBadge}>{t('activeSessions.current')}</span>
                            )}
                        </li>
                    );
                })}
            </ul>

            {confirming ? (
                <div className="danger-zone__confirm">
                    <span className="settings-hint">
                        {t('activeSessions.confirm', { n: otherCount })}
                    </span>
                    <button
                        type="button"
                        className="btn btn--danger-solid"
                        onClick={handleRevoke}
                        disabled={busy}
                    >
                        {busy ? t('activeSessions.revoking') : t('activeSessions.confirmYes')}
                    </button>
                    <button
                        type="button"
                        className="btn btn--ghost"
                        onClick={() => setConfirming(false)}
                        disabled={busy}
                    >
                        {t('activeSessions.cancel')}
                    </button>
                </div>
            ) : (
                <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => { setError(null); setConfirming(true); }}
                    disabled={otherCount === 0}
                    title={otherCount === 0 ? t('activeSessions.onlyThisDevice') : undefined}
                >
                    {t('activeSessions.revokeOthers')}
                </button>
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

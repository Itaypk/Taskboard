import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ApiError,
    fetchChannelLinks,
    startChannelLink,
    unlinkChannel,
    type ChannelLink,
    type ChannelLinkCode,
} from '../api';
import styles from './MessagingApps.module.css';
import { HelpTip } from './HelpTip';

/** Brand names stay untranslated. */
const APP_NAMES: Record<string, string> = { whatsapp: 'WhatsApp' };

/** How often a pending link checks whether the code has arrived. */
export const LINK_POLL_MS = 3000;

interface Pending {
    channel: string;
    code: ChannelLinkCode;
    expired: boolean;
}

/**
 * Chat apps linked for quick-add (`docs/MESSAGING-CHANNELS.md`, section 3). Connecting issues a
 * short-lived code the user sends to the bot from the chat app; the server links the account when
 * the code arrives, so this polls until it does or the code expires. Renders nothing when the
 * instance offers no channel and the user has none linked.
 */
export function MessagingApps() {
    const { t } = useTranslation();
    const [channels, setChannels] = useState<ChannelLink[] | null>(null);
    const [pending, setPending] = useState<Pending | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        const result = await fetchChannelLinks();
        setChannels(result.channels);
        return result.channels;
    }, []);

    useEffect(() => {
        let cancelled = false;
        fetchChannelLinks()
            .then(result => { if (!cancelled) setChannels(result.channels); })
            .catch(e => console.error('Failed to load messaging apps', e));
        return () => { cancelled = true; };
    }, []);

    // While a code is out, poll until the account is linked or the code expires.
    useEffect(() => {
        if (!pending || pending.expired) return;
        const timer = window.setInterval(() => {
            if (Date.now() >= Date.parse(pending.code.expiresAt)) {
                setPending(p => (p ? { ...p, expired: true } : p));
                return;
            }
            load()
                .then(result => {
                    if (result.some(c => c.channel === pending.channel && c.link)) setPending(null);
                })
                .catch(() => { /* transient; the next tick retries */ });
        }, LINK_POLL_MS);
        return () => window.clearInterval(timer);
    }, [pending, load]);

    const fail = (e: unknown, key = 'messagingApps.errors.failed') => {
        if (!(e instanceof ApiError && e.status === 401)) setError(t(key));
    };

    const handleConnect = async (channel: string) => {
        setBusy(true);
        setError(null);
        try {
            setPending({ channel, code: await startChannelLink(channel), expired: false });
        } catch (e) {
            fail(e, e instanceof ApiError && e.status === 403 ? 'messagingApps.errors.notClaimed' : undefined);
        } finally {
            setBusy(false);
        }
    };

    const handleDisconnect = async (channel: string, app: string) => {
        if (!window.confirm(t('messagingApps.confirmDisconnect', { app }))) return;
        setBusy(true);
        setError(null);
        try {
            await unlinkChannel(channel);
            await load();
        } catch (e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    };

    if (!channels || channels.length === 0) return null;

    return (
        <div className="field">
            <label className="field__label">
                {t('messagingApps.label')}
                <HelpTip text={t('messagingApps.hint')} />
            </label>

            {channels.map(({ channel, offered, link }) => {
                const app = APP_NAMES[channel] ?? channel;
                const code = pending?.channel === channel ? pending : null;
                return (
                    <div key={channel} className={styles.channel}>
                        <div className={styles.row}>
                            <span className={styles.app}>{app}</span>
                            {link ? (
                                <>
                                    <span className={styles.status}>
                                        {link.display
                                            ? t('messagingApps.connectedAs', { display: link.display })
                                            : t('messagingApps.connected')}
                                    </span>
                                    <button
                                        type="button"
                                        className="btn btn--ghost"
                                        onClick={() => handleDisconnect(channel, app)}
                                        disabled={busy}
                                    >
                                        {t('messagingApps.disconnect')}
                                    </button>
                                </>
                            ) : (
                                offered && !code && (
                                    <button
                                        type="button"
                                        className="btn btn--ghost"
                                        onClick={() => handleConnect(channel)}
                                        disabled={busy}
                                    >
                                        {t('messagingApps.connect')}
                                    </button>
                                )
                            )}
                        </div>

                        {code && !link && (
                            <div className={styles.pending}>
                                {code.expired ? (
                                    <p>{t('messagingApps.expired')}</p>
                                ) : (
                                    <>
                                        <p>{t('messagingApps.sendCode', { app })}</p>
                                        <code className={styles.code}>{code.code.code}</code>
                                    </>
                                )}
                                <div className={styles.actions}>
                                    {code.expired ? (
                                        <button
                                            type="button"
                                            className="btn btn--ghost"
                                            onClick={() => handleConnect(channel)}
                                            disabled={busy}
                                        >
                                            {t('messagingApps.newCode')}
                                        </button>
                                    ) : (
                                        code.code.url && (
                                            <a
                                                className="btn btn--primary"
                                                href={code.code.url}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                            >
                                                {t('messagingApps.openApp', { app })}
                                            </a>
                                        )
                                    )}
                                    <button type="button" className="btn btn--ghost" onClick={() => setPending(null)}>
                                        {t('messagingApps.cancel')}
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                );
            })}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

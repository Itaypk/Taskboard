import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ApiError,
    fetchCalendarDelivery,
    previewCalendarDeliverySwitch,
    rotateCalendarFeed,
    updateCalendarDelivery,
    type CalendarDelivery,
    type CalendarDeliveryState,
} from '../api';
import { formatRelative } from '../utils';
import { HelpTip } from './HelpTip';
import styles from './CalendarDeliverySection.module.css';

const OPTIONS: CalendarDelivery[] = ['NONE', 'EMAIL', 'FEED'];

interface Props {
    /** Opens the section where the email address is verified. */
    onVerifyEmail: () => void;
    /** Hears the current mode once loaded and after every switch, for the section list's summary. */
    onDeliveryChange?: (delivery: CalendarDelivery) => void;
}

/**
 * Settings › Calendar (GitHub issue #346): how planned blocks reach the user's
 * calendar. Off, emailed invitations, or a subscribed calendar (the secret feed).
 *
 * Saves through its own endpoint rather than the settings form: a switch cancels or sends
 * invitations, so it's confirmed with the real count first and then applied at once.
 * A second card, "Let the planner see my calendar" (#347), is meant to sit beside this one.
 */
export function CalendarDeliverySection({ onVerifyEmail, onDeliveryChange }: Props) {
    const { t } = useTranslation();
    const [state, setState] = useState<CalendarDeliveryState | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    useEffect(() => {
        let cancelled = false;
        fetchCalendarDelivery()
            .then(result => { if (!cancelled) setState(result); })
            .catch(e => console.error('Failed to load calendar delivery', e));
        return () => { cancelled = true; };
    }, []);

    const delivery = state?.delivery;
    useEffect(() => {
        if (delivery) onDeliveryChange?.(delivery);
    }, [delivery, onDeliveryChange]);

    const run = async (action: () => Promise<CalendarDeliveryState | null>) => {
        setBusy(true);
        setError(null);
        setCopied(false);
        try {
            const next = await action();
            if (next) setState(next);
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('calendarDelivery.errors.failed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const handleSelect = (target: CalendarDelivery) => {
        if (!state || target === state.delivery) return;
        void run(async () => {
            const preview = await previewCalendarDeliverySwitch(target);
            const messages: string[] = [];
            if (preview.invitationsToCancel > 0) {
                messages.push(t('calendarDelivery.confirm.cancel', { count: preview.invitationsToCancel }));
            }
            if (preview.invitationsToSend > 0) {
                messages.push(t('calendarDelivery.confirm.send', { count: preview.invitationsToSend }));
            }
            if (messages.length > 0 && !window.confirm(messages.join('\n\n'))) return null;
            return updateCalendarDelivery(target);
        });
    };

    const handleRotate = () => {
        if (!window.confirm(t('calendarDelivery.feed.confirmRotate'))) return;
        void run(rotateCalendarFeed);
    };

    const handleCopy = async () => {
        if (!state?.feed) return;
        try {
            await navigator.clipboard.writeText(state.feed.url);
            setCopied(true);
        } catch {
            // Clipboard access can be denied; the link is selectable on screen either way.
            setCopied(false);
        }
    };

    const feed = state?.feed ?? null;
    const lastFetched = feed?.lastFetchedAt ? formatRelative(feed.lastFetchedAt) : null;

    return (
        <div className="field">
            <p className="settings-hint">{t('calendarDelivery.hint')}</p>

            <section className={styles.card} aria-labelledby="calendar-outgoing-title">
                <h3 id="calendar-outgoing-title" className={styles.title}>
                    {t('calendarDelivery.outgoing.title')}
                    {state && <HelpTip text={t(`calendarDelivery.outgoing.modeHelp.${state.delivery}`)} />}
                </h3>

                {state && (
                    <>
                        <div className={styles.options} role="radiogroup" aria-labelledby="calendar-outgoing-title">
                            {OPTIONS.map(option => {
                                const disabled = busy || (option === 'EMAIL' && !state.emailAvailable && state.delivery !== 'EMAIL');
                                return (
                                    <label
                                        key={option}
                                        className={`${styles.option} ${state.delivery === option ? styles.optionSelected : ''} ${disabled ? styles.optionDisabled : ''}`}
                                    >
                                        <input
                                            type="radio"
                                            name="calendar-delivery"
                                            value={option}
                                            checked={state.delivery === option}
                                            disabled={disabled}
                                            onChange={() => handleSelect(option)}
                                        />
                                        <span className={styles.optionText}>
                                            <span>{t(`calendarDelivery.outgoing.options.${option}`)}</span>
                                            <small className={styles.optionHint}>{t(`calendarDelivery.outgoing.optionHints.${option}`)}</small>
                                        </span>
                                    </label>
                                );
                            })}
                        </div>

                        {!state.emailAvailable && state.delivery !== 'EMAIL' && (
                            <p className="settings-hint">
                                {t('calendarDelivery.outgoing.emailUnavailable')}{' '}
                                <button type="button" className="link-btn" onClick={onVerifyEmail}>
                                    {t('calendarDelivery.outgoing.verifyEmail')}
                                </button>
                            </p>
                        )}

                        {state.delivery === 'FEED' && feed && (
                            <>
                                <div className={styles.actions}>
                                    <a className="btn btn--primary" href={feed.googleUrl} target="_blank" rel="noopener noreferrer">
                                        {t('calendarDelivery.feed.google')}
                                    </a>
                                    <a className="btn btn--ghost" href={feed.webcalUrl}>
                                        {t('calendarDelivery.feed.webcal')}
                                    </a>
                                    <button type="button" className="btn btn--ghost" onClick={handleCopy} disabled={busy}>
                                        {copied ? t('calendarDelivery.feed.copied') : t('calendarDelivery.feed.copy')}
                                    </button>
                                </div>
                                <code className={styles.url}>{feed.url}</code>
                                <p className={`${styles.status} ${lastFetched ? styles.statusLive : ''}`}>
                                    <span className={styles.dot} aria-hidden="true" />
                                    {lastFetched
                                        ? t('calendarDelivery.feed.lastFetched', { when: lastFetched })
                                        : t('calendarDelivery.feed.neverFetched')}
                                </p>
                                <div className={styles.secret}>
                                    <button type="button" className="btn btn--ghost" onClick={handleRotate} disabled={busy}>
                                        {t('calendarDelivery.feed.rotate')}
                                    </button>
                                    <HelpTip text={t('calendarDelivery.feed.secretHint')} />
                                </div>
                            </>
                        )}
                    </>
                )}
            </section>

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}

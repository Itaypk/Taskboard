import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ApiError,
    addCalendarSource,
    deleteCalendarSource,
    fetchCalendarSources,
    refreshCalendarSource,
    updateCalendarSource,
    type CalendarSource,
    type CalendarSourceDetail,
} from '../api';
import { formatRelative } from '../utils';
import { HelpTip } from './HelpTip';
import styles from './CalendarSourcesSection.module.css';

/** Mirrors `CalendarSourceService.MAX_SOURCES` / `MAX_LABEL_LENGTH`. */
const MAX_SOURCES = 5;
const MAX_LABEL_LENGTH = 40;
const DETAILS: CalendarSourceDetail[] = ['TITLES', 'BUSY'];

/** Error codes the add endpoint answers with, beyond the read errors in `CalendarSourceError`. */
const KNOWN_ERRORS = new Set([
    'NOT_HTTPS', 'BLOCKED_ADDRESS', 'NOT_FOUND', 'UNAUTHORIZED', 'NOT_A_CALENDAR',
    'TOO_LARGE', 'TIMEOUT', 'UNREACHABLE', 'PARSE_ERROR', 'LIMIT_REACHED', 'DUPLICATE', 'RATE_LIMITED',
]);

/**
 * Settings › Calendar, second card (GitHub issue #347): calendars the planner reads when a planning
 * chat starts, each added by its secret iCal address. The address is sent once and never shown again;
 * a row shows only its name and host. Adding reads the calendar first, so a broken address is refused
 * on the spot with a line saying why.
 *
 * Rendered only when the instance has AI and the feature on (`calendarSources` in the public config).
 */
export function CalendarSourcesSection() {
    const { t } = useTranslation();
    const [sources, setSources] = useState<CalendarSource[] | null>(null);
    const [url, setUrl] = useState('');
    const [label, setLabel] = useState('');
    const [adding, setAdding] = useState(false);
    const [addError, setAddError] = useState<string | null>(null);
    const [busyId, setBusyId] = useState<string | null>(null);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [draftLabel, setDraftLabel] = useState('');
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        fetchCalendarSources()
            .then(result => { if (!cancelled) setSources(result); })
            .catch(e => console.error('Failed to load calendar sources', e));
        return () => { cancelled = true; };
    }, []);

    const replace = (updated: CalendarSource) =>
        setSources(current => current?.map(s => (s.id === updated.id ? updated : s)) ?? current);

    const runOnRow = async (id: string, action: () => Promise<void>) => {
        setBusyId(id);
        setError(null);
        try {
            await action();
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 401)) setError(t('calendarSources.errors.failed'));
        } finally {
            setBusyId(null);
        }
    };

    const handleAdd = async (event: FormEvent) => {
        event.preventDefault();
        if (!url.trim() || adding) return;
        setAdding(true);
        setAddError(null);
        try {
            const added = await addCalendarSource(url.trim(), label.trim() || null, 'TITLES');
            setSources(current => [...(current ?? []), added]);
            setUrl('');
            setLabel('');
        } catch (e) {
            const code = e instanceof ApiError ? e.code : undefined;
            setAddError(code && KNOWN_ERRORS.has(code)
                ? t(`calendarSources.errors.${code}`, { max: MAX_SOURCES })
                : t('calendarSources.errors.failed'));
        } finally {
            setAdding(false);
        }
    };

    const handleDetail = (source: CalendarSource, detail: CalendarSourceDetail) =>
        void runOnRow(source.id, async () => replace(await updateCalendarSource(source.id, { detail })));

    const handleRemove = (source: CalendarSource) => {
        if (!window.confirm(t('calendarSources.confirmRemove', { name: source.label }))) return;
        void runOnRow(source.id, async () => {
            await deleteCalendarSource(source.id);
            setSources(current => current?.filter(s => s.id !== source.id) ?? current);
        });
    };

    const handleRefresh = (source: CalendarSource) =>
        void runOnRow(source.id, async () => replace(await refreshCalendarSource(source.id)));

    const startRename = (source: CalendarSource) => {
        setEditingId(source.id);
        setDraftLabel(source.label);
    };

    const finishRename = (source: CalendarSource) => {
        setEditingId(null);
        const next = draftLabel.trim();
        if (!next || next === source.label) return;
        void runOnRow(source.id, async () => replace(await updateCalendarSource(source.id, { label: next })));
    };

    const handleRenameKey = (event: KeyboardEvent<HTMLInputElement>, source: CalendarSource) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            finishRename(source);
        } else if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation(); // keep the dialog open
            setEditingId(null);
        }
    };

    return (
        <section className={styles.card} aria-labelledby="calendar-incoming-title">
            <h3 id="calendar-incoming-title" className={styles.title}>
                {t('calendarSources.title')}
                <HelpTip text={t('calendarSources.help')} />
            </h3>

            {sources && sources.length > 0 && (
                <ul className={styles.list}>
                    {sources.map(source => {
                        const busy = busyId === source.id;
                        return (
                            <li key={source.id} className={styles.row}>
                                <div className={styles.rowMain}>
                                    {editingId === source.id ? (
                                        <input
                                            className={`field__input ${styles.renameInput}`}
                                            value={draftLabel}
                                            maxLength={MAX_LABEL_LENGTH}
                                            aria-label={t('calendarSources.nameLabel')}
                                            autoFocus
                                            onChange={e => setDraftLabel(e.target.value)}
                                            onBlur={() => finishRename(source)}
                                            onKeyDown={e => handleRenameKey(e, source)}
                                        />
                                    ) : (
                                        <button
                                            type="button"
                                            className={styles.name}
                                            title={t('calendarSources.rename')}
                                            onClick={() => startRename(source)}
                                        >
                                            {source.label}
                                        </button>
                                    )}
                                    <span className={styles.host}>{source.host}</span>
                                    <select
                                        className={`field__select ${styles.detail}`}
                                        aria-label={t('calendarSources.detailLabel', { name: source.label })}
                                        value={source.detail}
                                        disabled={busy}
                                        onChange={e => handleDetail(source, e.target.value as CalendarSourceDetail)}
                                    >
                                        {DETAILS.map(detail => (
                                            <option key={detail} value={detail}>{t(`calendarSources.details.${detail}`)}</option>
                                        ))}
                                    </select>
                                    <button
                                        type="button"
                                        className="btn btn--ghost btn--sm"
                                        disabled={busy}
                                        onClick={() => handleRemove(source)}
                                    >
                                        {t('calendarSources.remove')}
                                    </button>
                                </div>
                                <SourceStatus source={source} busy={busy} onRefresh={() => handleRefresh(source)} />
                            </li>
                        );
                    })}
                </ul>
            )}

            {sources && (sources.length >= MAX_SOURCES ? (
                <p className="settings-hint">{t('calendarSources.limit', { max: MAX_SOURCES })}</p>
            ) : (
                <form className={styles.add} onSubmit={handleAdd} noValidate>
                    <div className={styles.addFields}>
                        <label className={styles.addField}>
                            <span>{t('calendarSources.urlLabel')}</span>
                            <input
                                className={`field__input ${addError ? 'field__input--error' : ''}`}
                                type="url"
                                inputMode="url"
                                autoComplete="off"
                                spellCheck={false}
                                maxLength={2048}
                                placeholder="https://…/basic.ics"
                                value={url}
                                disabled={adding}
                                aria-invalid={addError ? true : undefined}
                                aria-describedby={addError ? 'calendar-source-add-error' : undefined}
                                onChange={e => { setUrl(e.target.value); setAddError(null); }}
                            />
                        </label>
                        <label className={styles.addField}>
                            <span>{t('calendarSources.nameLabel')}</span>
                            <input
                                className="field__input"
                                type="text"
                                maxLength={MAX_LABEL_LENGTH}
                                placeholder={t('calendarSources.namePlaceholder')}
                                value={label}
                                disabled={adding}
                                onChange={e => setLabel(e.target.value)}
                            />
                        </label>
                    </div>
                    {addError && <p id="calendar-source-add-error" className={`settings-error ${styles.addError}`}>{addError}</p>}
                    <div className={styles.addActions}>
                        <button type="submit" className="btn btn--primary btn--sm" disabled={adding || !url.trim()}>
                            {adding ? t('calendarSources.reading') : t('calendarSources.add')}
                        </button>
                        <span className={styles.whereHelp}>
                            {t('calendarSources.where')}
                            <HelpTip text={t('calendarSources.whereHelp')} />
                        </span>
                    </div>
                </form>
            ))}

            {error && <p className="settings-error">{error}</p>}
        </section>
    );
}

function SourceStatus({ source, busy, onRefresh }: { source: CalendarSource; busy: boolean; onRefresh: () => void }) {
    const { t } = useTranslation();
    if (source.lastError) {
        return (
            <p className={`${styles.status} ${styles.statusError}`}>
                <span className={styles.dot} aria-hidden="true" />
                <span>
                    {t(`calendarSources.errors.${source.lastError}`)}{' '}
                    <button type="button" className="link-btn" disabled={busy} onClick={onRefresh}>
                        {busy ? t('calendarSources.reading') : t('calendarSources.checkAgain')}
                    </button>
                </span>
            </p>
        );
    }
    if (!source.lastSuccessAt) return null;
    return (
        <p className={`${styles.status} ${styles.statusOk}`}>
            <span className={styles.dot} aria-hidden="true" />
            {t('calendarSources.lastRead', {
                when: formatRelative(source.lastSuccessAt),
                count: source.lastEventCount ?? 0,
            })}
        </p>
    );
}

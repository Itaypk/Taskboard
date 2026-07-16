import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ApiError,
  fetchPlanningEntry,
  fetchPlanningTranscript,
  fetchPlanForWeek,
  fetchEventsForWeek,
  fetchPlans,
  startPlanning,
  replyPlanning,
  revisePlanning,
  abandonPlanning,
  type PlanningEntry,
  type PlanningTurn,
  type PlanSummary,
  type RenderedMessage,
} from '../api';
import type { CurrentPlan, OneOffEvent } from '../types';
import MarkdownRenderer from './MarkdownRenderer';
import { ConfirmDialog } from './ConfirmDialog';
import { EventsSection } from './EventsSection';
import { PlanDetails } from './PlanDetails';
import { formatDate } from '../i18n/format';
import i18n from '../i18n';
import styles from './WeeklyPlanDrawer.module.css';

interface WeeklyPlanDrawerProps {
  open: boolean;
  onClose: () => void;
  /** The plan for the week containing today (the board's anchor), shown read-only as the default view. */
  currentPlan: CurrentPlan | null;
  /** Open a task from the plan's task list. */
  onTaskClick: (taskId: string) => void;
  onTaskContextMenu?: (e: React.MouseEvent, taskId: string) => void;
  /** Called whenever a turn finalizes a plan, so the board can refresh the read-only plan view. */
  onFinalized: () => void;
}

interface UserTurn {
  id: number;
  role: 'user';
  text: string;
}
interface AssistantTurn {
  id: number;
  role: 'assistant';
  message: RenderedMessage;
}
type Turn = UserTurn | AssistantTurn;

const DONE = 'DONE';

export function WeeklyPlanDrawer({ open, onClose, currentPlan, onTaskClick, onTaskContextMenu, onFinalized }: WeeklyPlanDrawerProps) {
  const { t } = useTranslation();
  const [view, setView] = useState<'overview' | 'chat'>('overview');
  const [entry, setEntry] = useState<PlanningEntry | null>(null);
  const [entryLoading, setEntryLoading] = useState(false);
  const [planIndex, setPlanIndex] = useState<PlanSummary[]>([]);
  // The week currently shown in the overview; null until the entry loads.
  const [viewedWeekStart, setViewedWeekStart] = useState<string | null>(null);
  // A fetched plan for a non-current week (the current week reuses the `currentPlan` prop).
  const [otherPlan, setOtherPlan] = useState<CurrentPlan | null>(null);
  const [otherPlanWeek, setOtherPlanWeek] = useState<string | null>(null);
  const [planLoading, setPlanLoading] = useState(false);
  // One-off events for the currently-viewed week (fetched independently of the plan, so they
  // surface even when the week has no finalized plan).
  const [events, setEvents] = useState<OneOffEvent[]>([]);
  const [eventsWeek, setEventsWeek] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [transcript, setTranscript] = useState<Turn[]>([]);
  const [phase, setPhase] = useState<string>('');
  const [thinking, setThinking] = useState(false);
  const [input, setInput] = useState('');
  const [confirmLeave, setConfirmLeave] = useState(false);
  // Set when "Plan this/next week" would overwrite an existing finalized plan for that week.
  const [overrideConfirm, setOverrideConfirm] = useState<{ offset: 'CURRENT' | 'NEXT'; planId: string } | null>(null);

  const turnSeq = useRef(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const nextId = () => ++turnSeq.current;

  const thisWeekStart = entry?.thisWeek.weekStart ?? null;

  const loadOverview = useCallback(() => {
    setEntryLoading(true);
    Promise.all([fetchPlanningEntry(), fetchPlans()])
      .then(([e, plans]) => {
        setEntry(e);
        setPlanIndex(plans);
        // Default to the week containing today; its plan is the `currentPlan` prop (no extra fetch).
        setViewedWeekStart(e.thisWeek.weekStart);
        setOtherPlan(null);
        setOtherPlanWeek(null);
      })
      .catch(() => {/* toast already surfaced by api layer */})
      .finally(() => setEntryLoading(false));
  }, []);

  // On open, resume an in-flight chat if one is still live; otherwise (re)load the overview.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!open) return;
    if (sessionId && view === 'chat' && phase !== DONE) return;
    setView('overview');
    loadOverview();
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  /* eslint-enable react-hooks/set-state-in-effect */

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // Keep the latest message in view as the conversation grows.
  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
  }, [transcript, thinking]);

  // Grow the composer to fit its content, capped by max-height in CSS.
  const resizeInput = useCallback(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, []);
  useEffect(() => { if (input === '') resizeInput(); }, [input, resizeInput]);

  // ── Week navigation ─────────────────────────────────────────────────────────
  // Navigable weeks = every week that has a finalized plan, plus this week and next
  // week (so they can always be planned even when empty). ISO dates sort chronologically.
  const navWeeks = (() => {
    const set = new Set<string>();
    planIndex.forEach(p => set.add(p.weekStart));
    if (entry) { set.add(entry.thisWeek.weekStart); set.add(entry.nextWeek.weekStart); }
    return [...set].sort();
  })();
  const navIdx = viewedWeekStart ? navWeeks.indexOf(viewedWeekStart) : -1;
  const prevWeek = navIdx > 0 ? navWeeks[navIdx - 1] : null;
  const nextWeek = navIdx >= 0 && navIdx < navWeeks.length - 1 ? navWeeks[navIdx + 1] : null;

  // The plan shown for the currently-viewed week: the prop for this week, a fetched plan otherwise.
  const isCurrentWeekView = viewedWeekStart != null && viewedWeekStart === thisWeekStart;
  const viewedPlan = isCurrentWeekView
    ? currentPlan
    : (otherPlanWeek === viewedWeekStart ? otherPlan : null);

  const selectWeek = useCallback((week: string) => {
    setViewedWeekStart(week);
    if (week === thisWeekStart) return; // reuses the currentPlan prop, no fetch
    setPlanLoading(true);
    fetchPlanForWeek(week)
      .then(p => { setOtherPlan(p); setOtherPlanWeek(week); })
      .catch(() => { setOtherPlan(null); setOtherPlanWeek(week); })
      .finally(() => setPlanLoading(false));
  }, [thisWeekStart]);

  // Fetch one-off events whenever the viewed week changes. Independent of the plan fetch above so
  // events still surface on weeks the user hasn't planned.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!viewedWeekStart) { setEvents([]); setEventsWeek(null); return; }
    if (eventsWeek === viewedWeekStart) return;
    fetchEventsForWeek(viewedWeekStart)
      .then(es => { setEvents(es); setEventsWeek(viewedWeekStart); })
      .catch(() => { setEvents([]); setEventsWeek(viewedWeekStart); });
  }, [viewedWeekStart, eventsWeek]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const handleEventCancelled = useCallback((eventId: string) => {
    setEvents(prev => prev.filter(e => e.id !== eventId));
  }, []);

  const applyTurn = useCallback((turn: PlanningTurn) => {
    setSessionId(turn.sessionId);
    setPhase(turn.phase);
    if (turn.messages.length > 0) {
      setTranscript(prev => [
        ...prev,
        ...turn.messages.map(message => ({ id: nextId(), role: 'assistant' as const, message })),
      ]);
    }
    if (turn.phase === DONE) onFinalized();
  }, [onFinalized]);

  const resetToOverview = useCallback(() => {
    setSessionId(null);
    setTranscript([]);
    setPhase('');
    setView('overview');
    loadOverview();
  }, [loadOverview]);

  // Return to the overview without abandoning a live session — it stays resumable via "Continue".
  const backToOverview = useCallback(() => {
    setView('overview');
    loadOverview();
  }, [loadOverview]);

  // Begin a brand-new conversation (start a week, or revise a completed plan).
  const begin = useCallback(async (action: () => Promise<PlanningTurn>) => {
    setTranscript([]);
    setPhase('');
    setThinking(true);
    setView('chat');
    try {
      applyTurn(await action());
    } catch {
      resetToOverview();
    } finally {
      setThinking(false);
    }
  }, [applyTurn, resetToOverview]);

  // Starting a fresh session for a week that already has a finalized plan would create a second plan
  // that supersedes it. Warn first and let the user revise the existing plan instead of overwriting.
  const planForWeek = useCallback((offset: 'CURRENT' | 'NEXT') => {
    const weekStart = offset === 'CURRENT' ? entry?.thisWeek.weekStart : entry?.nextWeek.weekStart;
    const existingPlanId = weekStart ? (planIndex.find(p => p.weekStart === weekStart)?.id ?? null) : null;
    if (existingPlanId) {
      setOverrideConfirm({ offset, planId: existingPlanId });
      return;
    }
    void begin(() => startPlanning(offset));
  }, [entry, planIndex, begin]);

  // Resume an in-flight session by reconstructing its transcript from the server (the React-only
  // transcript is lost on reload). Falls back to the overview if the session is gone (409).
  const resume = useCallback(async (id: string) => {
    setTranscript([]);
    setPhase('');
    setThinking(true);
    setView('chat');
    try {
      const transcriptResult = await fetchPlanningTranscript(id);
      setSessionId(transcriptResult.sessionId);
      setPhase(transcriptResult.phase);
      setTranscript(transcriptResult.messages.map(m => m.role === 'user'
        ? { id: nextId(), role: 'user', text: m.text }
        : { id: nextId(), role: 'assistant', message: { type: m.type, text: m.text, completions: m.completions, options: m.options } }));
    } catch {
      resetToOverview();
    } finally {
      setThinking(false);
    }
  }, [resetToOverview]);

  const send = useCallback(async (text: string, optionId?: string) => {
    if (!sessionId || thinking) return;
    const display = text.trim();
    if (display) setTranscript(prev => [...prev, { id: nextId(), role: 'user', text: display }]);
    setInput('');
    setThinking(true);
    try {
      const body = optionId ? { optionId, text: display || undefined } : { text: display };
      applyTurn(await replyPlanning(sessionId, body));
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) resetToOverview();
    } finally {
      setThinking(false);
    }
  }, [sessionId, thinking, applyTurn, resetToOverview]);

  // Explicitly abandon the in-progress session and return to the overview. Abandoning an
  // ACTIVE session drops it server-side, so the board's read-only plan falls back to the last
  // finalized plan — hence onFinalized() to refresh it.
  const leaveSession = useCallback(async () => {
    if (sessionId) { try { await abandonPlanning(sessionId); } catch {/* ignore */} }
    onFinalized();
    resetToOverview();
  }, [sessionId, onFinalized, resetToOverview]);

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (input.trim()) void send(input);
  };

  const onInputKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter sends; Shift+Enter inserts a newline.
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (input.trim() && !inputDisabled) void send(input);
    }
  };

  const lastIndex = transcript.length - 1;
  const inputDisabled = thinking || phase === DONE || !sessionId;

  const weekEnd = viewedWeekStart ? isoWeekEnd(viewedWeekStart) : null;

  // Where the viewed week sits relative to today's week — gates which planning actions make sense.
  // Past weeks can't be planned; while looking ahead, "plan this week" is a backward step, so it's hidden.
  const weekRel: 'past' | 'current' | 'future' =
    viewedWeekStart == null || thisWeekStart == null || viewedWeekStart === thisWeekStart
      ? 'current'
      : viewedWeekStart < thisWeekStart ? 'past' : 'future';

  return (
    <>
      <div className={`overlay${open ? ' overlay--open' : ''}`} onClick={onClose} />
      <aside
        className={`drawer${open ? ' drawer--open' : ''}`}
        inert={!open}
        role="dialog"
        aria-modal="true"
        aria-label={t('weeklyPlanDrawer.ariaLabel')}
      >
        <div className="drawer__header">
          <div className={styles.headerLeft}>
            {view === 'chat' && (
              <button type="button" className={styles.backBtn} onClick={backToOverview} aria-label={t('weeklyPlanDrawer.backToPlan')}>←</button>
            )}
            <div className={styles.titleGroup}>
              <span className="drawer__label">{view === 'chat' ? t('weeklyPlanDrawer.titleChat') : t('weeklyPlanDrawer.titleOverview')}</span>
            </div>
          </div>
          <div className={styles.headerActions}>
            {view === 'chat' && phase !== DONE && sessionId && (
              <button type="button" className={styles.leaveBtn} onClick={() => setConfirmLeave(true)}>
                {t('weeklyPlanDrawer.leaveSession')}
              </button>
            )}
            <button className="drawer__close" onClick={onClose} aria-label={t('weeklyPlanDrawer.close')}>×</button>
          </div>
        </div>

        <div className="drawer__body" ref={bodyRef}>
          {view === 'overview' ? (
            <div className={styles.overview}>
              {viewedWeekStart && weekEnd && (
                <div className={styles.weekNav}>
                  <button
                    type="button"
                    className={styles.weekNavBtn}
                    onClick={() => prevWeek && selectWeek(prevWeek)}
                    disabled={!prevWeek}
                    aria-label={t('weeklyPlanDrawer.prevWeekAria')}
                  >‹</button>
                  <div className={styles.weekNavLabel}>
                    <span className={styles.weekRelative}>{relativeWeekLabel(viewedWeekStart, thisWeekStart, entry?.nextWeek.weekStart ?? null)}</span>
                    <span className={styles.weekRange}>{formatWeekRange(viewedWeekStart, weekEnd)}</span>
                  </div>
                  <button
                    type="button"
                    className={styles.weekNavBtn}
                    onClick={() => nextWeek && selectWeek(nextWeek)}
                    disabled={!nextWeek}
                    aria-label={t('weeklyPlanDrawer.nextWeekAria')}
                  >›</button>
                </div>
              )}

              {planLoading ? (
                <p className={styles.entryHint}>{t('weeklyPlanDrawer.loading')}</p>
              ) : viewedPlan ? (
                <>
                  <PlanDetails plan={viewedPlan} onTaskClick={onTaskClick} onTaskContextMenu={onTaskContextMenu} />
                  <EventsSection events={events} onCancelled={handleEventCancelled} />
                </>
              ) : (
                <>
                  <div className={styles.empty}>
                    <p className={styles.emptyTitle}>{t('weeklyPlanDrawer.noPlanTitle')}</p>
                    <p className={styles.emptyHint}>
                      {weekRel === 'past'
                        ? t('weeklyPlanDrawer.emptyHintPast')
                        : t('weeklyPlanDrawer.emptyHintFuture')}
                    </p>
                  </div>
                  <EventsSection events={events} onCancelled={handleEventCancelled} />
                </>
              )}
              <OverviewActions
                entry={entry}
                viewedPlan={viewedPlan}
                weekRel={weekRel}
                loading={entryLoading}
                busy={thinking}
                onStart={planForWeek}
                onRevise={id => void begin(() => revisePlanning(id))}
                onContinue={id => void resume(id)}
                onAbandon={async id => { try { await abandonPlanning(id); } catch {/* ignore */} onFinalized(); resetToOverview(); }}
              />
            </div>
          ) : (
            <div className={styles.chat}>
              {transcript.map((turn, i) => turn.role === 'user' ? (
                <div key={turn.id} className={`${styles.msg} ${styles.msgUser}`}>
                  <div className={styles.bubbleUser}>{turn.text}</div>
                </div>
              ) : (
                <AssistantBubble
                  key={turn.id}
                  message={turn.message}
                  interactive={i === lastIndex && phase !== DONE && !thinking}
                  onChoose={(label, id) => void send(label, id)}
                  onChip={text => void send(text)}
                />
              ))}
              {thinking && <div className={styles.thinking}><span /><span /><span /></div>}
              {phase === DONE && (
                <div className={styles.doneBar}>
                  <span className={styles.doneLabel}>{t('weeklyPlanDrawer.planFinalized')}</span>
                  <button type="button" className={styles.entryBtn} onClick={resetToOverview}>{t('weeklyPlanDrawer.backToPlan')}</button>
                </div>
              )}
            </div>
          )}
        </div>

        {view === 'chat' && phase !== DONE && (
          <form className={styles.inputRow} onSubmit={onSubmit}>
            <textarea
              ref={inputRef}
              rows={1}
              className={styles.input}
              value={input}
              onChange={e => setInput(e.target.value)}
              onInput={resizeInput}
              onKeyDown={onInputKeyDown}
              placeholder={thinking ? t('weeklyPlanDrawer.thinkingPlaceholder') : t('weeklyPlanDrawer.typeMessagePlaceholder')}
              disabled={inputDisabled}
              aria-label={t('weeklyPlanDrawer.messageAria')}
            />
            <button type="submit" className={styles.sendBtn} disabled={inputDisabled || !input.trim()}>{t('weeklyPlanDrawer.send')}</button>
          </form>
        )}
      </aside>

      <ConfirmDialog
        open={confirmLeave}
        title={t('weeklyPlanDrawer.confirmLeave.title')}
        message={t('weeklyPlanDrawer.confirmLeave.message')}
        confirmLabel={t('weeklyPlanDrawer.confirmLeave.confirmLabel')}
        danger
        onConfirm={() => { void leaveSession(); }}
        onClose={() => setConfirmLeave(false)}
      />

      <OverridePlanDialog
        open={overrideConfirm !== null}
        offset={overrideConfirm?.offset ?? 'CURRENT'}
        onRevise={() => {
          const id = overrideConfirm?.planId;
          setOverrideConfirm(null);
          if (id) void begin(() => revisePlanning(id));
        }}
        onPlanFresh={() => {
          const offset = overrideConfirm?.offset;
          setOverrideConfirm(null);
          if (offset) void begin(() => startPlanning(offset));
        }}
        onClose={() => setOverrideConfirm(null)}
      />
    </>
  );
}

/**
 * Three-way confirmation shown when "Plan this/next week" would overwrite an existing finalized plan
 * for that week: revise the existing plan, replace it with a fresh session, or cancel.
 */
function OverridePlanDialog({ open, offset, onRevise, onPlanFresh, onClose }: {
  open: boolean;
  offset: 'CURRENT' | 'NEXT';
  onRevise: () => void;
  onPlanFresh: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const whichWeek = offset === 'CURRENT' ? t('weeklyPlanDrawer.thisWeekWord') : t('weeklyPlanDrawer.nextWeekWord');
  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="override-title" style={{ width: 400 }}>
        <div className="modal__header">
          <span className="modal__title" id="override-title">{t('weeklyPlanDrawer.override.title', { week: whichWeek })}</span>
        </div>
        <div className="modal__body" style={{ gap: 0, paddingBottom: 8 }}>
          <p style={{ margin: 0, fontSize: 14, lineHeight: 1.55, color: 'var(--ink-soft)' }}>
            {t('weeklyPlanDrawer.override.body', { week: whichWeek })}
          </p>
        </div>
        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>{t('weeklyPlanDrawer.override.cancel')}</button>
          <button type="button" className="btn btn--danger" onClick={onPlanFresh}>{t('weeklyPlanDrawer.override.planFromScratch')}</button>
          <button type="button" className="btn btn--primary" onClick={onRevise}>{t('weeklyPlanDrawer.override.revise')}</button>
        </div>
      </div>
    </div>
  );
}

function AssistantBubble({ message, interactive, onChoose, onChip }: {
  message: RenderedMessage;
  interactive: boolean;
  onChoose: (label: string, id: string) => void;
  onChip: (text: string) => void;
}) {
  return (
    <div className={`${styles.msg} ${styles.msgAssistant}`}>
      <div className={styles.bubbleAssistant}>
        <MarkdownRenderer content={message.text} />
      </div>
      {message.type === 'choice' && interactive && message.options.length > 0 && (
        <div className={styles.choiceList}>
          {message.options.map(opt => (
            <button key={opt.id} type="button" className={styles.choiceBtn} onClick={() => onChoose(opt.label, opt.id)}>
              {opt.label}
            </button>
          ))}
        </div>
      )}
      {message.type === 'text' && interactive && message.completions.length > 0 && (
        <div className={styles.chips}>
          {message.completions.map((c, i) => (
            <button key={i} type="button" className={styles.chip} onClick={() => onChip(c)}>{c}</button>
          ))}
        </div>
      )}
    </div>
  );
}

function OverviewActions({ entry, viewedPlan, weekRel, loading, busy, onStart, onRevise, onContinue, onAbandon }: {
  entry: PlanningEntry | null;
  viewedPlan: CurrentPlan | null;
  /** The viewed week relative to today's week — decides which planning actions are offered. */
  weekRel: 'past' | 'current' | 'future';
  loading: boolean;
  busy: boolean;
  onStart: (offset: 'CURRENT' | 'NEXT') => void;
  onRevise: (sessionId: string) => void;
  onContinue: (sessionId: string) => void;
  onAbandon: (sessionId: string) => void;
}) {
  const { t } = useTranslation();
  if (loading || !entry) {
    return <p className={styles.entryHint}>{t('weeklyPlanDrawer.loading')}</p>;
  }

  const aiOff = !entry.aiAvailable;
  // Disable starting / resuming / revising when AI is unavailable to the caller — either they
  // opted out themselves or every board they belong to has a co-member who opted out. The plan
  // itself stays readable; only the AI-driven actions are gated.
  const aiHint = aiOff ? (
    <p className={styles.actionsNote}>
      {t('weeklyPlanDrawer.aiDisabledHint')}
    </p>
  ) : null;

  if (entry.activeSessionId) {
    const id = entry.activeSessionId;
    return (
      <div className={styles.actions}>
        {aiHint ?? <p className={styles.actionsNote}>{t('weeklyPlanDrawer.sessionInProgress')}</p>}
        <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onContinue(id)}>
          {t('weeklyPlanDrawer.continuePlanning')}
        </button>
        <button type="button" className={styles.entryBtn} disabled={busy} onClick={() => onAbandon(id)}>
          {t('weeklyPlanDrawer.abandonStartOver')}
        </button>
      </div>
    );
  }

  // A past week can't be planned or revised — offer no planning actions for it.
  if (weekRel === 'past') return null;

  // The viewed week's plan can be revised in place when it's finalized.
  const revisable = viewedPlan && viewedPlan.status === 'completed' ? viewedPlan.id : null;
  // "Plan this week" targets the current week, so it's only relevant while viewing it; looking ahead,
  // it would be a step backward. "Plan next week" stays available on both the current and future views.
  const showPlanThisWeek = weekRel === 'current';
  return (
    <div className={styles.actions}>
      {aiHint}
      {revisable && (
        <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onRevise(revisable)}>
          {t('weeklyPlanDrawer.revisePlan')}
        </button>
      )}
      {showPlanThisWeek && (
        <button type="button" className={`${styles.entryBtn} ${revisable ? '' : styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onStart('CURRENT')}>
          {t('weeklyPlanDrawer.planThisWeek')}<span className={styles.entryDates}>{formatRange(entry.thisWeek.weekStart, entry.thisWeek.weekEnd)}</span>
        </button>
      )}
      <button type="button" className={`${styles.entryBtn} ${(!revisable && !showPlanThisWeek) ? styles.entryPrimary : ''}`} disabled={busy || aiOff} onClick={() => onStart('NEXT')}>
        {t('weeklyPlanDrawer.planNextWeek')}<span className={styles.entryDates}>{formatRange(entry.nextWeek.weekStart, entry.nextWeek.weekEnd)}</span>
      </button>
    </div>
  );
}

/** End date (ISO) of the 7-day week starting at `weekStart`. */
function isoWeekEnd(weekStart: string): string {
  const d = new Date(`${weekStart}T00:00:00`);
  d.setDate(d.getDate() + 6);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** "Last week" / "This week" / "Next week" relative to today's week, else a blank label. */
function relativeWeekLabel(weekStart: string, thisWeekStart: string | null, nextWeekStart: string | null): string {
  if (!thisWeekStart) return '';
  if (weekStart === thisWeekStart) return i18n.t('weeklyPlanDrawer.relative.thisWeek');
  if (nextWeekStart && weekStart === nextWeekStart) return i18n.t('weeklyPlanDrawer.relative.nextWeek');
  const ms = new Date(`${weekStart}T00:00:00`).getTime() - new Date(`${thisWeekStart}T00:00:00`).getTime();
  const weeks = Math.round(ms / (7 * 24 * 60 * 60 * 1000));
  if (weeks === -1) return i18n.t('weeklyPlanDrawer.relative.lastWeek');
  if (weeks < 0) return i18n.t('weeklyPlanDrawer.relative.weeksAgo', { count: -weeks });
  if (weeks > 1) return i18n.t('weeklyPlanDrawer.relative.inWeeks', { count: weeks });
  return '';
}

function formatRange(weekStart: string, weekEnd: string): string {
  const start = new Date(`${weekStart}T00:00:00`);
  const end = new Date(`${weekEnd}T00:00:00`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return '';
  const startMonth = formatDate(start, { month: 'short' });
  const endMonth = formatDate(end, { month: 'short' });
  return startMonth === endMonth
    ? `${startMonth} ${start.getDate()}–${end.getDate()}`
    : `${startMonth} ${start.getDate()} – ${endMonth} ${end.getDate()}`;
}

// Like formatRange but includes the year — used in the overview week navigator.
function formatWeekRange(weekStart: string, weekEnd: string): string {
  const start = new Date(`${weekStart}T00:00:00`);
  const end = new Date(`${weekEnd}T00:00:00`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return '';
  const year = start.getFullYear();
  const startMonth = formatDate(start, { month: 'short' });
  const endMonth = formatDate(end, { month: 'short' });
  return startMonth === endMonth
    ? `${startMonth} ${start.getDate()}–${end.getDate()}, ${year}`
    : `${startMonth} ${start.getDate()} – ${endMonth} ${end.getDate()}, ${year}`;
}

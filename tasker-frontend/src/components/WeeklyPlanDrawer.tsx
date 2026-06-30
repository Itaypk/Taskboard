import { useCallback, useEffect, useRef, useState } from 'react';
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
      const t = await fetchPlanningTranscript(id);
      setSessionId(t.sessionId);
      setPhase(t.phase);
      setTranscript(t.messages.map(m => m.role === 'user'
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

  return (
    <>
      <div className={`overlay${open ? ' overlay--open' : ''}`} onClick={onClose} />
      <aside
        className={`drawer${open ? ' drawer--open' : ''}`}
        aria-hidden={!open}
        role="dialog"
        aria-modal="true"
        aria-label="Weekly plan"
      >
        <div className="drawer__header">
          <div className={styles.headerLeft}>
            {view === 'chat' && (
              <button type="button" className={styles.backBtn} onClick={backToOverview} aria-label="Back to plan">←</button>
            )}
            <div className={styles.titleGroup}>
              <span className="drawer__label">{view === 'chat' ? 'Weekly planning' : 'Weekly plan'}</span>
            </div>
          </div>
          <div className={styles.headerActions}>
            {view === 'chat' && phase !== DONE && sessionId && (
              <button type="button" className={styles.leaveBtn} onClick={() => setConfirmLeave(true)}>
                Leave session
              </button>
            )}
            <button className="drawer__close" onClick={onClose} aria-label="Close">×</button>
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
                    aria-label="Previous week"
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
                    aria-label="Next week"
                  >›</button>
                </div>
              )}

              {planLoading ? (
                <p className={styles.entryHint}>Loading…</p>
              ) : viewedPlan ? (
                <>
                  <PlanDetails plan={viewedPlan} onTaskClick={onTaskClick} onTaskContextMenu={onTaskContextMenu} />
                  <EventsSection events={events} />
                </>
              ) : (
                <>
                  <div className={styles.empty}>
                    <p className={styles.emptyTitle}>No plan for this week.</p>
                    <p className={styles.emptyHint}>Plan it with the assistant below, or start a session on Telegram.</p>
                  </div>
                  <EventsSection events={events} />
                </>
              )}
              <OverviewActions
                entry={entry}
                viewedPlan={viewedPlan}
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
                  <span className={styles.doneLabel}>Plan finalized</span>
                  <button type="button" className={styles.entryBtn} onClick={resetToOverview}>Back to plan</button>
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
              placeholder={thinking ? 'Thinking…' : 'Type a message…'}
              disabled={inputDisabled}
              aria-label="Message"
            />
            <button type="submit" className={styles.sendBtn} disabled={inputDisabled || !input.trim()}>Send</button>
          </form>
        )}
      </aside>

      <ConfirmDialog
        open={confirmLeave}
        title="Leave planning session?"
        message="This discards the in-progress conversation. Your existing plan stays as it is."
        confirmLabel="Leave"
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
  const whichWeek = offset === 'CURRENT' ? 'this week' : 'next week';
  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="override-title" style={{ width: 400 }}>
        <div className="modal__header">
          <span className="modal__title" id="override-title">You already have a plan for {whichWeek}</span>
        </div>
        <div className="modal__body" style={{ gap: 0, paddingBottom: 8 }}>
          <p style={{ margin: 0, fontSize: 14, lineHeight: 1.55, color: 'var(--ink-soft)' }}>
            Planning {whichWeek} from scratch starts a new session that will replace the existing
            plan when you finalize it. Revise the current plan instead to keep what's already scheduled.
          </p>
        </div>
        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button type="button" className="btn btn--danger" onClick={onPlanFresh}>Plan from scratch</button>
          <button type="button" className="btn btn--primary" onClick={onRevise}>Revise</button>
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

function OverviewActions({ entry, viewedPlan, loading, busy, onStart, onRevise, onContinue, onAbandon }: {
  entry: PlanningEntry | null;
  viewedPlan: CurrentPlan | null;
  loading: boolean;
  busy: boolean;
  onStart: (offset: 'CURRENT' | 'NEXT') => void;
  onRevise: (sessionId: string) => void;
  onContinue: (sessionId: string) => void;
  onAbandon: (sessionId: string) => void;
}) {
  if (loading || !entry) {
    return <p className={styles.entryHint}>Loading…</p>;
  }

  const aiOff = !entry.aiAvailable;
  // Disable starting / resuming / revising when AI is unavailable to the caller — either they
  // opted out themselves or every board they belong to has a co-member who opted out. The plan
  // itself stays readable; only the AI-driven actions are gated.
  const aiHint = aiOff ? (
    <p className={styles.actionsNote}>
      AI planning is disabled. Re-enable it in Settings → Assistant, or ask a co-member who
      opted out to do the same on a board you share.
    </p>
  ) : null;

  if (entry.activeSessionId) {
    const id = entry.activeSessionId;
    return (
      <div className={styles.actions}>
        {aiHint ?? <p className={styles.actionsNote}>A planning session is in progress.</p>}
        <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onContinue(id)}>
          Continue planning
        </button>
        <button type="button" className={styles.entryBtn} disabled={busy} onClick={() => onAbandon(id)}>
          Abandon &amp; start over
        </button>
      </div>
    );
  }

  // The viewed week's plan can be revised in place when it's finalized.
  const revisable = viewedPlan && viewedPlan.status === 'completed' ? viewedPlan.id : null;
  return (
    <div className={styles.actions}>
      {aiHint}
      {revisable && (
        <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onRevise(revisable)}>
          Revise this plan
        </button>
      )}
      <button type="button" className={`${styles.entryBtn} ${revisable ? '' : styles.entryPrimary}`} disabled={busy || aiOff} onClick={() => onStart('CURRENT')}>
        Plan this week<span className={styles.entryDates}>{formatRange(entry.thisWeek.weekStart, entry.thisWeek.weekEnd)}</span>
      </button>
      <button type="button" className={styles.entryBtn} disabled={busy || aiOff} onClick={() => onStart('NEXT')}>
        Plan next week<span className={styles.entryDates}>{formatRange(entry.nextWeek.weekStart, entry.nextWeek.weekEnd)}</span>
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
  if (weekStart === thisWeekStart) return 'This week';
  if (nextWeekStart && weekStart === nextWeekStart) return 'Next week';
  const ms = new Date(`${weekStart}T00:00:00`).getTime() - new Date(`${thisWeekStart}T00:00:00`).getTime();
  const weeks = Math.round(ms / (7 * 24 * 60 * 60 * 1000));
  if (weeks === -1) return 'Last week';
  if (weeks < 0) return `${-weeks} weeks ago`;
  if (weeks > 1) return `In ${weeks} weeks`;
  return '';
}

function formatRange(weekStart: string, weekEnd: string): string {
  const start = new Date(`${weekStart}T00:00:00`);
  const end = new Date(`${weekEnd}T00:00:00`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return '';
  const startMonth = start.toLocaleDateString(undefined, { month: 'short' });
  const endMonth = end.toLocaleDateString(undefined, { month: 'short' });
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
  const startMonth = start.toLocaleDateString(undefined, { month: 'short' });
  const endMonth = end.toLocaleDateString(undefined, { month: 'short' });
  return startMonth === endMonth
    ? `${startMonth} ${start.getDate()}–${end.getDate()}, ${year}`
    : `${startMonth} ${start.getDate()} – ${endMonth} ${end.getDate()}, ${year}`;
}

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ApiError,
  fetchPlanningEntry,
  startPlanning,
  replyPlanning,
  revisePlanning,
  abandonPlanning,
  type PlanningEntry,
  type PlanningTurn,
  type RenderedMessage,
} from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import { ConfirmDialog } from './ConfirmDialog';
import styles from './PlanningDrawer.module.css';

interface PlanningDrawerProps {
  open: boolean;
  onClose: () => void;
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

export function PlanningDrawer({ open, onClose, onFinalized }: PlanningDrawerProps) {
  const [view, setView] = useState<'entry' | 'chat'>('entry');
  const [entry, setEntry] = useState<PlanningEntry | null>(null);
  const [entryLoading, setEntryLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [transcript, setTranscript] = useState<Turn[]>([]);
  const [phase, setPhase] = useState<string>('');
  const [thinking, setThinking] = useState(false);
  const [input, setInput] = useState('');
  const [confirmLeave, setConfirmLeave] = useState(false);

  const turnSeq = useRef(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const nextId = () => ++turnSeq.current;

  const loadEntry = useCallback(() => {
    setEntryLoading(true);
    fetchPlanningEntry()
      .then(setEntry)
      .catch(() => {/* toast already surfaced by api layer */})
      .finally(() => setEntryLoading(false));
  }, []);

  // On open, resume an in-flight chat if one is still live; otherwise (re)load the entry screen.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!open) return;
    if (sessionId && view === 'chat' && phase !== DONE) return;
    setView('entry');
    loadEntry();
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

  const resetToEntry = useCallback(() => {
    setSessionId(null);
    setTranscript([]);
    setPhase('');
    setView('entry');
    loadEntry();
  }, [loadEntry]);

  // Begin a brand-new conversation (start a week, or revise a completed plan).
  const begin = useCallback(async (action: () => Promise<PlanningTurn>) => {
    setTranscript([]);
    setPhase('');
    setThinking(true);
    setView('chat');
    try {
      applyTurn(await action());
    } catch {
      resetToEntry();
    } finally {
      setThinking(false);
    }
  }, [applyTurn, resetToEntry]);

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
      if (e instanceof ApiError && e.status === 409) resetToEntry();
    } finally {
      setThinking(false);
    }
  }, [sessionId, thinking, applyTurn, resetToEntry]);

  // Explicitly abandon the in-progress session and return to the entry screen. Abandoning an
  // ACTIVE session drops it server-side, so the board's read-only plan falls back to the last
  // finalized plan — hence onFinalized() to refresh it.
  const leaveSession = useCallback(async () => {
    if (sessionId) { try { await abandonPlanning(sessionId); } catch {/* ignore */} }
    onFinalized();
    resetToEntry();
  }, [sessionId, onFinalized, resetToEntry]);

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (input.trim()) void send(input);
  };

  const lastIndex = transcript.length - 1;
  const inputDisabled = thinking || phase === DONE || !sessionId;

  return (
    <>
      <div className={`overlay${open ? ' overlay--open' : ''}`} onClick={onClose} />
      <aside
        className={`drawer${open ? ' drawer--open' : ''}`}
        aria-hidden={!open}
        role="dialog"
        aria-modal="true"
        aria-label="Weekly planning"
      >
        <div className="drawer__header">
          <span className="drawer__label">Weekly planning</span>
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
          {view === 'entry' ? (
            <EntryView
              entry={entry}
              loading={entryLoading}
              busy={thinking}
              onStart={offset => void begin(() => startPlanning(offset))}
              onRevise={id => void begin(() => revisePlanning(id))}
              onContinue={id => { setSessionId(id); setPhase(''); setView('chat'); }}
              onAbandon={async id => { try { await abandonPlanning(id); } catch {/* ignore */} onFinalized(); resetToEntry(); }}
            />
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
                  <button type="button" className={styles.entryBtn} onClick={resetToEntry}>Start a new plan</button>
                </div>
              )}
            </div>
          )}
        </div>

        {view === 'chat' && phase !== DONE && (
          <form className={styles.inputRow} onSubmit={onSubmit}>
            <input
              className={styles.input}
              value={input}
              onChange={e => setInput(e.target.value)}
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
    </>
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

function EntryView({ entry, loading, busy, onStart, onRevise, onContinue, onAbandon }: {
  entry: PlanningEntry | null;
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

  if (entry.activeSessionId) {
    const id = entry.activeSessionId;
    return (
      <div className={styles.entry}>
        <p className={styles.entryTitle}>A planning session is in progress.</p>
        <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy} onClick={() => onContinue(id)}>
          Continue planning
        </button>
        <button type="button" className={styles.entryBtn} disabled={busy} onClick={() => onAbandon(id)}>
          Abandon &amp; start over
        </button>
      </div>
    );
  }

  return (
    <div className={styles.entry}>
      <p className={styles.entryTitle}>Plan your week with the assistant.</p>
      {entry.revisableSessionId && (
        <div className={styles.summaryCard}>
          <span className={styles.summaryLabel}>Current plan</span>
          {entry.completedPlanSummary && <p className={styles.summaryText}>{entry.completedPlanSummary}</p>}
          <button type="button" className={`${styles.entryBtn} ${styles.entryPrimary}`} disabled={busy} onClick={() => onRevise(entry.revisableSessionId!)}>
            Revise this plan
          </button>
        </div>
      )}
      <button type="button" className={styles.entryBtn} disabled={busy} onClick={() => onStart('CURRENT')}>
        Plan this week<span className={styles.entryDates}>{formatRange(entry.thisWeek.weekStart, entry.thisWeek.weekEnd)}</span>
      </button>
      <button type="button" className={styles.entryBtn} disabled={busy} onClick={() => onStart('NEXT')}>
        Plan next week<span className={styles.entryDates}>{formatRange(entry.nextWeek.weekStart, entry.nextWeek.weekEnd)}</span>
      </button>
    </div>
  );
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

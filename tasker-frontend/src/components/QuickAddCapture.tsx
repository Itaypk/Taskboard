import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, abandonQuickAdd, replyQuickAdd, startQuickAdd, type QuickAddTurn, type RenderedMessage } from '../api';
import { AssistantBubble, ThinkingDots, UserBubble } from './ChatMessages';
import styles from './QuickAddCapture.module.css';

interface QuickAddCaptureProps {
  /** The board the captured tasks are saved to. */
  boardId: string;
  /** The question above the text box; it depends on where the capture is shown. */
  prompt: string;
  /** Called when the capture has written to the board, so the host can refresh it. */
  onBoardChanged: () => void;
  /** Called when the user dismisses a finished capture (saved, cancelled or failed). */
  onFinished?: () => void;
}

type Turn =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; message: RenderedMessage };

/** Mirrors the server's cap on a capture's text. */
const MAX_CHARS = 4000;

/**
 * AI-assisted quick-add: the user types what they need to do, the assistant drafts tasks from it,
 * and the user saves, adjusts or cancels the draft (sometimes after a clarifying question).
 * Self-contained, so it can sit wherever a board is open; today that is the planning drawer's
 * empty state.
 */
export function QuickAddCapture({ boardId, prompt, onBoardChanged, onFinished }: QuickAddCaptureProps) {
  const { t } = useTranslation();
  // idle: the text box. active: a draft is being reviewed. ended: saved, cancelled or failed.
  const [phase, setPhase] = useState<'idle' | 'active' | 'ended'>('idle');
  const [text, setText] = useState('');
  const [transcript, setTranscript] = useState<Turn[]>([]);
  const [reply, setReply] = useState('');
  const [thinking, setThinking] = useState(false);
  const turnSeq = useRef(0);
  const nextId = () => ++turnSeq.current;

  // The server keeps a capture until it ends or times out; drop it if the user walks away mid-draft.
  const abandonOnUnmount = useRef<(() => void) | null>(null);
  useEffect(() => {
    abandonOnUnmount.current = phase === 'active' ? () => { void abandonQuickAdd(boardId).catch(() => {}); } : null;
  }, [phase, boardId]);
  useEffect(() => () => abandonOnUnmount.current?.(), []);

  const notice = (message: string): Turn => (
    { id: nextId(), role: 'assistant', message: { type: 'text', text: message, completions: [], options: [] } }
  );

  const applyTurn = (turn: QuickAddTurn) => {
    const messages: Turn[] = turn.messages.map(message => ({ id: nextId(), role: 'assistant', message }));
    // A turn that came back empty would otherwise look like the capture silently stalled.
    if (messages.length === 0 && turn.active) messages.push(notice(t('quickAdd.noReply')));
    setTranscript(prev => [...prev, ...messages]);
    setPhase(turn.active ? 'active' : 'ended');
    if (turn.boardChanged) onBoardChanged();
  };

  const reset = useCallback(() => {
    setPhase('idle');
    setTranscript([]);
    setReply('');
  }, []);

  const start = async () => {
    const request = text.trim();
    if (!request || thinking) return;
    setTranscript([{ id: nextId(), role: 'user', text: request }]);
    setPhase('active');
    setThinking(true);
    try {
      applyTurn(await startQuickAdd(boardId, request));
      setText('');
    } catch {
      // The API layer already raised a toast; keep what was typed so it can be sent again.
      reset();
    } finally {
      setThinking(false);
    }
  };

  // `label` is what goes in the transcript. A chosen option is sent by id alone; typed text is
  // sent as text (an adjustment to the draft, or the answer to a clarifying question).
  const send = async (label: string, optionId?: string) => {
    const display = label.trim();
    if (!display || thinking || phase !== 'active') return;
    setTranscript(prev => [...prev, { id: nextId(), role: 'user', text: display }]);
    setReply('');
    setThinking(true);
    try {
      applyTurn(await replyQuickAdd(boardId, optionId ? { optionId } : { text: display }));
    } catch (e) {
      // 409: the capture is gone server-side (it timed out, or another tab started one).
      if (e instanceof ApiError && e.status === 409) {
        setTranscript(prev => [...prev, notice(t('quickAdd.expired'))]);
        setPhase('ended');
      } else {
        setTranscript(prev => [...prev, notice(t('quickAdd.turnFailed'))]);
      }
    } finally {
      setThinking(false);
    }
  };

  if (phase === 'idle') {
    return (
      <form className={styles.capture} onSubmit={e => { e.preventDefault(); void start(); }}>
        <p className={styles.prompt}>{prompt}</p>
        <textarea
          className={styles.input}
          rows={4}
          maxLength={MAX_CHARS}
          value={text}
          onChange={e => setText(e.target.value)}
          placeholder={t('quickAdd.placeholder')}
          aria-label={prompt}
        />
        <p className={styles.hint}>{t('quickAdd.hint')}</p>
        <button type="submit" className={styles.primaryBtn} disabled={!text.trim()}>
          {t('quickAdd.submit')}
        </button>
      </form>
    );
  }

  const lastIndex = transcript.length - 1;
  return (
    <div className={styles.capture}>
      <div className={styles.transcript}>
        {transcript.map((turn, i) => turn.role === 'user' ? (
          <UserBubble key={turn.id} text={turn.text} />
        ) : (
          <AssistantBubble
            key={turn.id}
            message={turn.message}
            interactive={i === lastIndex && phase === 'active' && !thinking}
            onChoose={(label, id) => void send(label, id)}
            onChip={chip => void send(chip)}
          />
        ))}
        {thinking && <ThinkingDots />}
      </div>
      {phase === 'active' ? (
        <form className={styles.replyRow} onSubmit={e => { e.preventDefault(); void send(reply); }}>
          <input
            className={styles.replyInput}
            value={reply}
            maxLength={MAX_CHARS}
            onChange={e => setReply(e.target.value)}
            placeholder={t('quickAdd.replyPlaceholder')}
            aria-label={t('quickAdd.replyPlaceholder')}
            disabled={thinking}
          />
          <button type="submit" className={styles.sendBtn} disabled={thinking || !reply.trim()}>
            {t('quickAdd.send')}
          </button>
        </form>
      ) : (
        <button type="button" className={styles.primaryBtn} onClick={() => { reset(); onFinished?.(); }}>
          {t('quickAdd.done')}
        </button>
      )}
    </div>
  );
}

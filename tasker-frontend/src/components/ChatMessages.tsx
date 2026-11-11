import type { RenderedMessage } from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import styles from './ChatMessages.module.css';

/** The pieces of an assistant conversation, shared by the planning drawer and quick-add capture. */

export function UserBubble({ text }: { text: string }) {
  return (
    <div className={`${styles.msg} ${styles.msgUser}`}>
      <div className={styles.bubbleUser}>{text}</div>
    </div>
  );
}

export function AssistantBubble({ message, interactive, onChoose, onChip }: {
  message: RenderedMessage;
  /** Whether the message's choices and suggestions can still be answered (only the latest one can). */
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

export function ThinkingDots() {
  return <div className={styles.thinking}><span /><span /><span /></div>;
}

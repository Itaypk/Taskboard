import { useId } from 'react';
import styles from './HelpTip.module.css';

interface HelpTipProps {
  /** The text shown in the tooltip and announced to assistive tech. */
  text: string;
  /** Side the tooltip floats to. Defaults to right; pick "left" near the right edge. */
  side?: 'right' | 'left';
}

/**
 * Inline help icon ("?") that reveals [text] on hover or keyboard focus. Used in the
 * settings dialog to keep explanatory copy out of the layout — labels stay scannable,
 * details are one cursor away.
 */
export function HelpTip({ text, side = 'right' }: HelpTipProps) {
  const tipId = useId();
  return (
    <span className={styles.wrap}>
      <button
        type="button"
        className={styles.trigger}
        aria-label={text}
        aria-describedby={tipId}
      >
        ?
      </button>
      <span
        id={tipId}
        role="tooltip"
        className={`${styles.bubble} ${side === 'left' ? styles.bubbleLeft : styles.bubbleRight}`}
      >
        {text}
      </span>
    </span>
  );
}

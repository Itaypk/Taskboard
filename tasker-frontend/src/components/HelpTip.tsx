import styles from './HelpTip.module.css';
import { Tooltip } from './Tooltip';

interface HelpTipProps {
  /** The text shown in the tooltip and announced to assistive tech. */
  text: string;
  /** Side the tooltip floats to. Defaults to right; pick "left" near the right edge. */
  side?: 'right' | 'left';
}

/**
 * Inline help icon ("?") that reveals [text] on hover or keyboard focus. Used in the
 * settings dialog to keep explanatory copy out of the layout — labels stay scannable,
 * details are one cursor away. The bubble itself is a portalled {@link Tooltip}.
 */
export function HelpTip({ text, side = 'right' }: HelpTipProps) {
  return (
    <Tooltip text={text} side={side}>
      <button type="button" className={styles.trigger} aria-label={text}>
        ?
      </button>
    </Tooltip>
  );
}

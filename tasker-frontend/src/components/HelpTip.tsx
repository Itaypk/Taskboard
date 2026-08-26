import styles from './HelpTip.module.css';
import { Tooltip } from './Tooltip';

interface HelpTipProps {
  /** The text shown in the tooltip and announced to assistive tech. */
  text: string;
  /** Inline side the tooltip floats to, in reading order. See {@link Tooltip}. */
  side?: 'end' | 'start';
}

/**
 * Inline help icon ("?") that reveals [text] on hover or keyboard focus. Used in the
 * settings dialog to keep explanatory copy out of the layout — labels stay scannable,
 * details are one cursor away. The bubble itself is a portalled {@link Tooltip}.
 */
export function HelpTip({ text, side = 'end' }: HelpTipProps) {
  return (
    <Tooltip text={text} side={side}>
      <button type="button" className={styles.trigger} aria-label={text}>
        ?
      </button>
    </Tooltip>
  );
}

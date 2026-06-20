import { cloneElement, useId, useRef, useState, type ReactElement } from 'react';
import { createPortal } from 'react-dom';
import styles from './Tooltip.module.css';

interface TooltipProps {
  /** The text shown in the bubble and announced to assistive tech. */
  text: string;
  /** Side the bubble grows towards. Defaults to right; pick "left" near the right edge. */
  side?: 'right' | 'left';
  /** The trigger element the bubble describes (e.g. an icon button). */
  children: ReactElement;
}

/**
 * Hover/focus bubble rendered through a portal to <body> with fixed positioning, so it escapes
 * the overflow clipping and stacking context of whatever container it lives in (modals, scrollable
 * drawers). Position is measured from the trigger each time it opens.
 */
export function Tooltip({ text, side = 'right', children }: TooltipProps) {
  const tipId = useId();
  const triggerRef = useRef<HTMLSpanElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const [visible, setVisible] = useState(false);

  const open = () => {
    const el = triggerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    setPos({ top: rect.top, left: side === 'left' ? rect.right : rect.left });
    // Mount first, then flip to visible on the next frame so the fade/slide-in transition runs.
    requestAnimationFrame(() => setVisible(true));
  };

  const close = () => {
    setVisible(false);
    setPos(null);
  };

  const trigger = cloneElement(children, {
    'aria-describedby': pos ? tipId : undefined,
  } as Partial<typeof children.props>);

  return (
    <span
      ref={triggerRef}
      className={styles.wrap}
      onMouseEnter={open}
      onMouseLeave={close}
      onFocus={open}
      onBlur={close}
    >
      {trigger}
      {pos &&
        createPortal(
          <span
            id={tipId}
            role="tooltip"
            className={[
              styles.bubble,
              side === 'left' ? styles.bubbleLeft : styles.bubbleRight,
              visible ? styles.bubbleVisible : '',
            ].join(' ')}
            style={{ top: pos.top, left: pos.left }}
          >
            {text}
          </span>,
          document.body,
        )}
    </span>
  );
}

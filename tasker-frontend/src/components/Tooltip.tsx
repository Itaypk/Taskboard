import { cloneElement, useId, useRef, useState, type ReactElement } from 'react';
import { createPortal } from 'react-dom';
import styles from './Tooltip.module.css';

interface TooltipProps {
  /** The text shown in the bubble and announced to assistive tech. */
  text: string;
  /**
   * Inline side the bubble grows towards, in reading order. Defaults to "end" (rightwards in
   * LTR, leftwards in RTL); pick "start" for a trigger near the container's inline-end edge.
   */
  side?: 'end' | 'start';
  /** The trigger element the bubble describes (e.g. an icon button). */
  children: ReactElement;
}

/**
 * Hover/focus bubble rendered through a portal to <body> with fixed positioning, so it escapes
 * the overflow clipping and stacking context of whatever container it lives in (modals, scrollable
 * drawers). Position is measured from the trigger each time it opens.
 */
export function Tooltip({ text, side = 'end', children }: TooltipProps) {
  const tipId = useId();
  const triggerRef = useRef<HTMLSpanElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const [visible, setVisible] = useState(false);

  const open = () => {
    const el = triggerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    // `left` here is a viewport coordinate, so the logical side has to be resolved against the
    // document direction: growing towards inline-start anchors the bubble's far edge to the
    // trigger's leading corner, which is the trigger's right edge in LTR and its left in RTL.
    const rtl = document.documentElement.dir === 'rtl';
    const growsBack = side === 'start';
    setPos({ top: rect.top, left: growsBack !== rtl ? rect.right : rect.left });
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
              side === 'start' ? styles.bubbleStart : styles.bubbleEnd,
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

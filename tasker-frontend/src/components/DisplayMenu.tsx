import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ViewMode } from '../types';
import { TEXT_SIZES, type TextSize } from '../textSize';
import styles from './DisplayMenu.module.css';

interface DisplayMenuProps {
  viewMode: ViewMode;
  onViewModeChange: (next: ViewMode) => void;
  textSize: TextSize;
  onTextSizeChange: (next: TextSize) => void;
}

const TEXT_SIZE_LABEL_KEYS: Record<TextSize, string> = {
  default: 'displayMenu.textSizes.default',
  large: 'displayMenu.textSizes.large',
  larger: 'displayMenu.textSizes.larger',
};

/**
 * Quick, per-device display controls: the board layout (cards vs. compact list) and the text size.
 * An icon-button in the paper-pill idiom (sibling to SortMenu) that opens a small popover of two
 * segmented controls. Choices apply instantly and the popover stays open, so the board can be
 * watched changing behind it. The button goes dark whenever either choice is off its default.
 */
export function DisplayMenu({ viewMode, onViewModeChange, textSize, onTextSizeChange }: DisplayMenuProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const layoutLabelId = useId();
  const sizeLabelId = useId();

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const active = viewMode !== 'board' || textSize !== 'default';

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`${styles.trigger} ${active ? styles.triggerActive : ''}`}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t('displayMenu.label')}
        title={t('displayMenu.label')}
        onClick={() => setOpen(o => !o)}
      >
        <DisplayIcon />
      </button>

      {open && (
        <div id={panelId} className={styles.panel} role="group" aria-label={t('displayMenu.label')}>
          <span id={layoutLabelId} className={styles.heading}>{t('displayMenu.layout')}</span>
          <div className={styles.segmented} role="radiogroup" aria-labelledby={layoutLabelId}>
            <Segment checked={viewMode === 'board'} onSelect={() => onViewModeChange('board')}>
              <GridIcon />{t('displayMenu.cards')}
            </Segment>
            <Segment checked={viewMode === 'compact'} onSelect={() => onViewModeChange('compact')}>
              <StackIcon />{t('displayMenu.compact')}
            </Segment>
          </div>

          <span id={sizeLabelId} className={styles.heading}>{t('displayMenu.textSize')}</span>
          <div className={styles.segmented} role="radiogroup" aria-labelledby={sizeLabelId}>
            {TEXT_SIZES.map(size => (
              <Segment
                key={size}
                checked={textSize === size}
                onSelect={() => onTextSizeChange(size)}
                ariaLabel={t(TEXT_SIZE_LABEL_KEYS[size])}
                className={styles[`size-${size}`]}
              >
                A
              </Segment>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

interface SegmentProps {
  checked: boolean;
  onSelect: () => void;
  ariaLabel?: string;
  className?: string;
  children: React.ReactNode;
}

function Segment({ checked, onSelect, ariaLabel, className, children }: SegmentProps) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      aria-label={ariaLabel}
      title={ariaLabel}
      className={[styles.segment, checked ? styles.segmentOn : '', className ?? ''].filter(Boolean).join(' ')}
      onClick={onSelect}
    >
      {children}
    </button>
  );
}

/** A screen with a gear at its corner: the conventional "display settings" glyph, in the header's line style. */
function DisplayIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M14.5 7V3.5A1.5 1.5 0 0 0 13 2H3a1.5 1.5 0 0 0-1.5 1.5v6A1.5 1.5 0 0 0 3 11h5" />
      <path d="M4.5 14h3.5M6.25 11v3" />
      <circle cx="12.25" cy="11.75" r="1.4" />
      <path d="M12.25 8.9v.6M12.25 14v.6M9.4 11.75h.6M14.5 11.75h.6M10.25 9.75l.4.4M13.85 13.35l.4.4M10.25 13.75l.4-.4M13.85 10.15l.4-.4" />
    </svg>
  );
}

/** Four cards: the pinboard layout. */
function GridIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="2" y="2" width="5" height="5" rx="1" />
      <rect x="9" y="2" width="5" height="5" rx="1" />
      <rect x="2" y="9" width="5" height="5" rx="1" />
      <rect x="9" y="9" width="5" height="5" rx="1" />
    </svg>
  );
}

/** Stacked lines: the compact list layout. */
function StackIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 4h11M3 8h11M3 12h11" />
    </svg>
  );
}

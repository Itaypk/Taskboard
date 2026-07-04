import type { ViewMode } from '../types';
import styles from './ViewToggle.module.css';

interface ViewToggleProps {
  value: ViewMode;
  onChange: (next: ViewMode) => void;
}

/**
 * Switches the board between the full pinboard and the compact stack. A single icon-button in the
 * paper-pill idiom (sibling to SortMenu), showing the *target* view's glyph and going dark while
 * compact is active — so the mode reads at a glance without spending header width.
 */
export function ViewToggle({ value, onChange }: ViewToggleProps) {
  const compact = value === 'compact';
  return (
    <button
      type="button"
      className={`${styles.trigger} ${compact ? styles.triggerActive : ''}`}
      aria-pressed={compact}
      aria-label={compact ? 'Switch to card view' : 'Switch to compact view'}
      title={compact ? 'Card view' : 'Compact view'}
      onClick={() => onChange(compact ? 'board' : 'compact')}
    >
      {compact ? <GridIcon /> : <StackIcon />}
    </button>
  );
}

/** Four cards — the pinboard you'd switch back to. */
function GridIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="2" y="2" width="5" height="5" rx="1" />
      <rect x="9" y="2" width="5" height="5" rx="1" />
      <rect x="2" y="9" width="5" height="5" rx="1" />
      <rect x="9" y="9" width="5" height="5" rx="1" />
    </svg>
  );
}

/** Stacked lines — the compact list you'd switch to. */
function StackIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 4h10M3 8h10M3 12h10" />
      <circle cx="1.4" cy="4" r="0.6" fill="currentColor" stroke="none" />
      <circle cx="1.4" cy="8" r="0.6" fill="currentColor" stroke="none" />
      <circle cx="1.4" cy="12" r="0.6" fill="currentColor" stroke="none" />
    </svg>
  );
}

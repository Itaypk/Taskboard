import { useEffect, useRef, useState } from 'react';
import { SORT_OPTIONS, type SortMode } from '../sort';
import styles from './SortMenu.module.css';

interface SortMenuProps {
  value: SortMode;
  onChange: (next: SortMode) => void;
}

/**
 * Sort control: an icon-button (sitting beside the board switcher, since ordering is board-specific)
 * that opens a small popover of field sorts. The button goes dark when a sort is active; which field
 * is showing is conveyed by the tooltip and the menu's checkmark rather than a label, so the button
 * keeps a fixed width.
 */
export function SortMenu({ value, onChange }: SortMenuProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const active = value !== 'none';
  const activeLabel = SORT_OPTIONS.find(o => o.id === value)?.label;
  const select = (mode: SortMode) => { onChange(mode); setOpen(false); };

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        type="button"
        className={`${styles.trigger} ${active ? styles.triggerActive : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={active ? `Sorted by ${activeLabel}. Change sort order` : 'Sort tasks'}
        title={active ? `Sorted by ${activeLabel}` : 'Sort tasks'}
        onClick={() => setOpen(o => !o)}
      >
        <SortIcon />
      </button>

      {open && (
        <div className={styles.menu} role="menu" aria-label="Sort tasks by">
          {SORT_OPTIONS.map(opt => (
            <MenuItem key={opt.id} label={opt.label} checked={value === opt.id} onSelect={() => select(opt.id)} />
          ))}
          <div className={styles.sep} role="separator" />
          <MenuItem label="None" checked={value === 'none'} onSelect={() => select('none')} />
        </div>
      )}
    </div>
  );
}

function MenuItem({ label, checked, onSelect }: { label: string; checked: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      role="menuitemradio"
      aria-checked={checked}
      className={`${styles.item} ${checked ? styles.itemActive : ''}`}
      onClick={onSelect}
    >
      <span className={styles.check} aria-hidden>{checked ? '✓' : ''}</span>
      {label}
    </button>
  );
}

function SortIcon() {
  // Stacked lines shrinking top→bottom with a down arrow — the conventional "sort" glyph.
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M2.5 4.5h7M2.5 8h4.5M2.5 11.5h2" />
      <path d="M12.5 3.5v9M10.5 10.5l2 2 2-2" />
    </svg>
  );
}

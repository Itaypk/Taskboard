import { useEffect, useRef, useState } from 'react';
import type { Board } from '../api';
import styles from './BrandBoard.module.css';

interface BrandBoardProps {
  boards: Board[];
  activeBoardId: string | null;
  onSwitch: (boardId: string) => void;
  onCreate: () => void;
  onRename: () => void;
  onDelete: () => void;
  /** Brand wordmark shown on the Dymo tape. */
  brandName?: string;
}

function ChevronIcon() {
  return (
    <svg className={styles.chev} width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 4.5L6 7.5L9 4.5" />
    </svg>
  );
}

/**
 * Brand + board switcher fused into one header unit: the Backlog.fyi tape with
 * the active board name as a paper tag pinned beside it, the tag itself being
 * the switcher trigger. Replaces BOTH the standalone `<span className="logo-tape">`
 * and `<BoardSwitcher>`.
 *
 * The two sit side by side so the unit stays a single row (no header-height
 * growth). On mobile the tape is hidden and the tag stands alone, freeing the
 * top row for the action buttons. The tag name is width-capped + ellipsized so
 * a long board name can never push the buttons onto a second row.
 *
 * Behaviour (outside-click + Escape to close, radio menu of boards plus manage
 * actions) is unchanged from the original BoardSwitcher.
 */
export function BrandBoard({
  boards,
  activeBoardId,
  onSwitch,
  onCreate,
  onRename,
  onDelete,
  brandName = 'Backlog.fyi',
}: BrandBoardProps) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onClick);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  const activeBoard = boards.find(b => b.id === activeBoardId);
  const run = (action: () => void) => { setOpen(false); action(); };

  return (
    <div className={styles.brand} ref={wrapRef}>
      <span className={`logo-tape ${styles.tape}`}>{brandName}</span>

      <div className={styles.switcher}>
        <button
          type="button"
          className={styles.label}
          onClick={() => setOpen(o => !o)}
          aria-haspopup="menu"
          aria-expanded={open}
          title={activeBoard?.name ?? 'Switch board'}
        >
          <span className={styles.labelName}>{activeBoard?.name ?? 'Board'}</span>
          <ChevronIcon />
        </button>

        {open && (
          <ul className={styles.menu} role="menu">
            {boards.map(board => (
              <li key={board.id} role="none">
                <button
                  type="button"
                  role="menuitemradio"
                  aria-checked={board.id === activeBoardId}
                  className={`${styles.item} ${board.id === activeBoardId ? styles.itemActive : ''}`}
                  onClick={() => run(() => onSwitch(board.id))}
                  title={board.name}
                >
                  <span className={styles.itemDot} aria-hidden />
                  <span className={styles.itemName}>{board.name}</span>
                </button>
              </li>
            ))}
            <li className={styles.divider} role="separator" />
            <li role="none">
              <button type="button" role="menuitem" className={styles.item} onClick={() => run(onCreate)}>+ New board</button>
            </li>
            <li role="none">
              <button type="button" role="menuitem" className={styles.item} onClick={() => run(onRename)}>Rename board</button>
            </li>
            {boards.length > 1 && (
              <li role="none">
                <button type="button" role="menuitem" className={`${styles.item} ${styles.itemDanger}`} onClick={() => run(onDelete)}>Delete board</button>
              </li>
            )}
          </ul>
        )}
      </div>
    </div>
  );
}

import { useEffect, useRef, useState } from 'react';
import type { Board } from '../api';
import styles from './BoardSwitcher.module.css';

interface BoardSwitcherProps {
  boards: Board[];
  activeBoardId: string | null;
  onSwitch: (boardId: string) => void;
  onCreate: () => void;
  onRename: () => void;
  onDelete: () => void;
}

function ChevronIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 4.5L6 7.5L9 4.5" />
    </svg>
  );
}

/**
 * Workspace-style board switcher in the header. Shows the active board's name; the dropdown lists
 * all boards (click to switch) and the board-management actions. Delete is hidden when only one
 * board remains (the backend enforces the same ">= 1 board" rule).
 */
export function BoardSwitcher({ boards, activeBoardId, onSwitch, onCreate, onRename, onDelete }: BoardSwitcherProps) {
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
    <div className={styles.wrap} ref={wrapRef}>
      <button
        type="button"
        className={styles.trigger}
        onClick={() => setOpen(o => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        title="Switch board"
      >
        <span className={styles.triggerName}>{activeBoard?.name ?? 'Board'}</span>
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
              >
                <span className={styles.itemDot} aria-hidden />
                <span className={styles.itemName}>{board.name}</span>
              </button>
            </li>
          ))}
          <li className={styles.divider} role="separator" />
          <li role="none">
            <button type="button" role="menuitem" className={styles.item} onClick={() => run(onCreate)}>
              + New board
            </button>
          </li>
          <li role="none">
            <button type="button" role="menuitem" className={styles.item} onClick={() => run(onRename)}>
              Rename board
            </button>
          </li>
          {boards.length > 1 && (
            <li role="none">
              <button
                type="button"
                role="menuitem"
                className={`${styles.item} ${styles.itemDanger}`}
                onClick={() => run(onDelete)}
              >
                Delete board
              </button>
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

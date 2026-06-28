import { useEffect, useState } from 'react';
import type { Board } from '../api';
import { fetchCategories } from '../api';
import type { Category } from '../types';
import styles from './MoveTaskModal.module.css';

interface MoveTaskModalProps {
  open: boolean;
  taskTitle: string;
  /** The task's category name on the current board, used to pre-select a same-named destination category. */
  currentCategoryLabel: string | null;
  /** Boards the task can move to — the caller already excludes the current board. */
  boards: Board[];
  onConfirm: (targetBoardId: string, categoryId: string) => void;
  onClose: () => void;
}

export function MoveTaskModal({
  open, taskTitle, currentCategoryLabel, boards, onConfirm, onClose,
}: MoveTaskModalProps) {
  const [selectedBoardId, setSelectedBoardId] = useState<string | null>(null);
  const [categories, setCategories] = useState<Category[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string>('');
  const [loadingCategories, setLoadingCategories] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // Reset the picker each time the modal opens for a new task.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (open) {
      setSelectedBoardId(null);
      setCategories([]);
      setSelectedCategoryId('');
      setError(false);
    }
  }, [open]);

  // Load the destination board's categories when one is picked, then pre-select a same-named
  // category if there is one (falling back to the first).
  useEffect(() => {
    if (!selectedBoardId) return;
    let cancelled = false;
    setLoadingCategories(true);
    setError(false);
    setCategories([]);
    setSelectedCategoryId('');
    fetchCategories(selectedBoardId)
      .then(cats => {
        if (cancelled) return;
        setCategories(cats);
        const match = currentCategoryLabel
          ? cats.find(c => c.label.toLowerCase() === currentCategoryLabel.toLowerCase())
          : undefined;
        setSelectedCategoryId(match?.id ?? cats[0]?.id ?? '');
      })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoadingCategories(false); });
    return () => { cancelled = true; };
  }, [selectedBoardId, currentCategoryLabel]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const canMove = !!selectedBoardId && !!selectedCategoryId && !loadingCategories;

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label="Move task" style={{ width: 420 }}>
        <div className="modal__header">
          <span className="modal__title">Move task</span>
        </div>

        <div className="modal__body">
          <p className={styles.subtitle}>
            Moving <strong>{taskTitle}</strong> to another board. Pick the destination board and the
            category it should land in; tags are recreated on the destination.
          </p>

          <div className="field">
            <label className="field__label">Destination board</label>
            <div className={styles.boardList} role="radiogroup" aria-label="Destination board">
              {boards.map(board => (
                <button
                  key={board.id}
                  type="button"
                  role="radio"
                  aria-checked={selectedBoardId === board.id}
                  className={`${styles.boardBtn} ${selectedBoardId === board.id ? styles.boardBtnSelected : ''}`}
                  onClick={() => setSelectedBoardId(board.id)}
                >
                  <span className={styles.boardName}>{board.name}</span>
                  {board.memberCount > 1 && <span className={styles.boardMeta}>{board.memberCount} members</span>}
                </button>
              ))}
            </div>
          </div>

          {selectedBoardId && (
            <div className="field">
              <label className="field__label" htmlFor="move-category">Category</label>
              {loadingCategories && <p className={styles.note}>Loading categories…</p>}
              {error && <p className={styles.note}>Couldn't load that board's categories. Try again.</p>}
              {!loadingCategories && !error && (
                <select
                  id="move-category"
                  className="field__select"
                  value={selectedCategoryId}
                  onChange={e => setSelectedCategoryId(e.target.value)}
                >
                  {categories.map(c => (
                    <option key={c.id} value={c.id}>{c.label}</option>
                  ))}
                </select>
              )}
            </div>
          )}
        </div>

        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={() => { if (canMove) onConfirm(selectedBoardId!, selectedCategoryId); }}
            disabled={!canMove}
          >
            Move
          </button>
        </div>
      </div>
    </div>
  );
}

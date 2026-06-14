import { useEffect, useState } from 'react';
import type { Board } from '../api';
import styles from './MoveTaskModal.module.css';

interface MoveTaskModalProps {
  open: boolean;
  taskTitle: string;
  /** Boards the task can move to — the caller already excludes the current board. */
  boards: Board[];
  onConfirm: (targetBoardId: string) => void;
  onClose: () => void;
}

export function MoveTaskModal({ open, taskTitle, boards, onConfirm, onClose }: MoveTaskModalProps) {
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // Reset the selection each time the modal opens for a new task.
  useEffect(() => { if (open) setSelected(null); }, [open]);

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
            Moving <strong>{taskTitle}</strong> to another board. Its category is matched by name on the
            destination (falling back to the first category), and tags are recreated there.
          </p>
          <div className={styles.boardList} role="radiogroup" aria-label="Destination board">
            {boards.map(board => (
              <button
                key={board.id}
                type="button"
                role="radio"
                aria-checked={selected === board.id}
                className={`${styles.boardBtn} ${selected === board.id ? styles.boardBtnSelected : ''}`}
                onClick={() => setSelected(board.id)}
              >
                <span className={styles.boardName}>{board.name}</span>
                {board.memberCount > 1 && <span className={styles.boardMeta}>{board.memberCount} members</span>}
              </button>
            ))}
          </div>
        </div>

        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={() => { if (selected) onConfirm(selected); }}
            disabled={!selected}
          >
            Move
          </button>
        </div>
      </div>
    </div>
  );
}

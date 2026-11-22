import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { QuickAddCapture } from './QuickAddCapture';

interface QuickAddModalProps {
  open: boolean;
  /** The board the captured tasks are saved to. */
  boardId: string;
  onClose: () => void;
  /** Called when the capture has written to the board, so the host can refresh it. */
  onBoardChanged: () => void;
}

/**
 * The web quick-add in a dialog, opened from the "bring over the tasks you already have" tutorial
 * card: a new user pastes the list they keep elsewhere and reviews the drafted tasks before they
 * are saved. An ad-hoc, AI-based import, no file format involved.
 */
export function QuickAddModal({ open, boardId, onClose, onBoardChanged }: QuickAddModalProps) {
  const { t } = useTranslation();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={t('quickAddModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{t('quickAddModal.title')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('quickAddModal.close')}>×</button>
        </div>
        <div className="modal__body">
          {/* Mounted only while open: closing unmounts the capture, which abandons an unfinished
              draft on the server, and reopening starts from an empty text box. */}
          {open && (
            <QuickAddCapture
              boardId={boardId}
              prompt={t('quickAddModal.prompt')}
              onBoardChanged={onBoardChanged}
              onFinished={onClose}
            />
          )}
        </div>
      </div>
    </div>
  );
}

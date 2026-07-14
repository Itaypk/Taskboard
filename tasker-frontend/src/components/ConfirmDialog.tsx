import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

export function ConfirmDialog({
  open, title, message, confirmLabel, danger = false, onConfirm, onClose,
}: ConfirmDialogProps) {
  const { t } = useTranslation();
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) {
      window.addEventListener('keydown', handler);
      confirmRef.current?.focus();
    }
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-message"
        style={{ width: 380 }}
      >
        <div className="modal__header">
          <span className="modal__title" id="confirm-title">{title}</span>
        </div>
        <div className="modal__body" style={{ gap: 0, paddingBottom: 8 }}>
          <p id="confirm-message" style={{ margin: 0, fontSize: 14, lineHeight: 1.55, color: 'var(--ink-soft)' }}>
            {message}
          </p>
        </div>
        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            {t('confirmDialog.cancel')}
          </button>
          <button
            ref={confirmRef}
            type="button"
            className={`btn ${danger ? 'btn--danger' : 'btn--primary'}`}
            onClick={() => { onConfirm(); onClose(); }}
          >
            {confirmLabel ?? t('confirmDialog.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

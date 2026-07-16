import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './ConfirmDialog.module.css';

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
  const titleId = useId();
  const messageId = useId();

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
        className={`modal ${styles.dialog}`}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={messageId}
      >
        <div className="modal__header">
          <span className="modal__title" id={titleId}>{title}</span>
        </div>
        <div className={`modal__body ${styles.body}`}>
          <p id={messageId} className={styles.message}>
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

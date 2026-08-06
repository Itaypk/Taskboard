import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Toggle } from './Toggle';

interface DuplicateBoardDialogProps {
  open: boolean;
  /** Name of the board being duplicated; used to derive the suggested copy name. */
  sourceBoardName: string;
  onConfirm: (name: string, resetTaskStatus: boolean) => Promise<void> | void;
  onClose: () => void;
}

/** Confirms a new board's name (defaulting to "<source> (copy)") and whether copied tasks reset to TODO. */
export function DuplicateBoardDialog({ open, sourceBoardName, onConfirm, onClose }: DuplicateBoardDialogProps) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [resetTaskStatus, setResetTaskStatus] = useState(true);
  const [busy, setBusy] = useState(false);
  const [wasOpen, setWasOpen] = useState(open);
  const inputRef = useRef<HTMLInputElement>(null);

  // Reset the field each time the dialog (re)opens, without an effect-driven setState loop.
  if (open && !wasOpen) {
    setWasOpen(true);
    setName(t('duplicateBoardDialog.defaultName', { name: sourceBoardName }).slice(0, 60));
    setResetTaskStatus(true);
    setBusy(false);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  }

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    inputRef.current?.focus();
    inputRef.current?.select();
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const trimmed = name.trim();
  const canSubmit = trimmed.length > 0 && trimmed.length <= 60 && !busy;

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    try {
      await onConfirm(trimmed, resetTaskStatus);
      onClose();
    } catch {
      // The api layer surfaces a toast; keep the dialog open so the user can retry.
      setBusy(false);
    }
  };

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="duplicate-board-title" style={{ width: 380 }}>
        <div className="modal__header">
          <span className="modal__title" id="duplicate-board-title">{t('duplicateBoardDialog.title')}</span>
        </div>
        <div className="modal__body" style={{ paddingBottom: 8 }}>
          <input
            ref={inputRef}
            type="text"
            className="field__input"
            value={name}
            maxLength={60}
            placeholder={t('boardNameDialog.namePlaceholder')}
            onChange={e => setName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void submit(); } }}
          />
          <div style={{ marginTop: 16 }}>
            <Toggle
              checked={resetTaskStatus}
              onChange={setResetTaskStatus}
              label={t('duplicateBoardDialog.resetTaskStatus')}
            />
          </div>
        </div>
        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>{t('boardNameDialog.cancel')}</button>
          <button type="button" className="btn btn--primary" disabled={!canSubmit} onClick={() => void submit()}>
            {t('duplicateBoardDialog.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

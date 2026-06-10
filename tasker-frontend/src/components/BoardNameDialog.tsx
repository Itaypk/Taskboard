import { useEffect, useRef, useState } from 'react';

interface BoardNameDialogProps {
  open: boolean;
  title: string;
  confirmLabel: string;
  initialValue?: string;
  onConfirm: (name: string) => Promise<void> | void;
  onClose: () => void;
}

/** Small single-field dialog for creating or renaming a board. Reuses the shared modal/btn styles. */
export function BoardNameDialog({ open, title, confirmLabel, initialValue = '', onConfirm, onClose }: BoardNameDialogProps) {
  const [name, setName] = useState(initialValue);
  const [busy, setBusy] = useState(false);
  const [wasOpen, setWasOpen] = useState(open);
  const inputRef = useRef<HTMLInputElement>(null);

  // Reset the field each time the dialog (re)opens, without an effect-driven setState loop.
  if (open && !wasOpen) { setWasOpen(true); setName(initialValue); setBusy(false); }
  else if (!open && wasOpen) { setWasOpen(false); }

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
      await onConfirm(trimmed);
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
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="board-name-title" style={{ width: 380 }}>
        <div className="modal__header">
          <span className="modal__title" id="board-name-title">{title}</span>
        </div>
        <div className="modal__body" style={{ paddingBottom: 8 }}>
          <input
            ref={inputRef}
            type="text"
            className="field__input"
            value={name}
            maxLength={60}
            placeholder="Board name"
            onChange={e => setName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void submit(); } }}
          />
        </div>
        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button type="button" className="btn btn--primary" disabled={!canSubmit} onClick={() => void submit()}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

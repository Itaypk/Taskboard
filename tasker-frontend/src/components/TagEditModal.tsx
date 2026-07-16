import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TagColorId } from '../types';
import { TAG_PALETTE } from '../types';

interface TagEditModalProps {
  open: boolean;
  initialLabel: string;
  initialColorId: TagColorId;
  /** True when the tag already exists on the board (the edit fans out to every task using it);
   *  false for a not-yet-saved draft tag, where the change is local to this task. */
  persists: boolean;
  onSave: (label: string, colorId: TagColorId) => void;
  onClose: () => void;
}

/** Small name + color editor opened by clicking a tag tape in the task drawer. */
export function TagEditModal({ open, initialLabel, initialColorId, persists, onSave, onClose }: TagEditModalProps) {
  const { t } = useTranslation();
  const [label, setLabel] = useState(initialLabel);
  const [colorId, setColorId] = useState<TagColorId>(initialColorId);
  const [seeded, setSeeded] = useState(false);

  // Render-phase seed on the open-edge (the pattern used elsewhere in the app), so each open
  // starts from the tag's current values rather than stale state.
  if (open && !seeded) {
    setSeeded(true);
    setLabel(initialLabel);
    setColorId(initialColorId);
  } else if (!open && seeded) {
    setSeeded(false);
  }

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const trimmed = label.trim();
  const canSave = trimmed.length > 0 && trimmed.length <= 64;

  return (
    <div className="modal-overlay modal-overlay--open" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={t('tagEditModal.title')} style={{ width: 340 }}>
        <div className="modal__header">
          <span className="modal__title">{t('tagEditModal.title')}</span>
          <button type="button" className="modal__close" onClick={onClose} aria-label={t('tagEditModal.close')}>✕</button>
        </div>

        <div className="modal__body">
          <div className="field">
            <label className="field__label" htmlFor="tag-edit-label">{t('tagEditModal.label')}</label>
            <input
              id="tag-edit-label"
              className="field__input"
              value={label}
              maxLength={64}
              autoFocus
              onChange={e => setLabel(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && canSave) onSave(trimmed, colorId); }}
              placeholder={t('tagEditModal.labelPlaceholder')}
            />
          </div>

          <div className="field">
            <label className="field__label">{t('tagEditModal.color')}</label>
            <div className="color-swatches">
              {TAG_PALETTE.map(c => (
                <button
                  key={c.id}
                  type="button"
                  className={`color-swatch${colorId === c.id ? ' color-swatch--selected' : ''}`}
                  style={{ background: c.text }}
                  onClick={() => setColorId(c.id as TagColorId)}
                  aria-label={c.id}
                />
              ))}
            </div>
          </div>

          {persists && (
            <p className="settings-hint">{t('tagEditModal.persistsHint')}</p>
          )}
        </div>

        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>{t('tagEditModal.cancel')}</button>
          <button type="button" className="btn btn--primary" disabled={!canSave} onClick={() => onSave(trimmed, colorId)}>
            {t('tagEditModal.save')}
          </button>
        </div>
      </div>
    </div>
  );
}

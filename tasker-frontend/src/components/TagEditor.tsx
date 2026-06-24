import { useState } from 'react';
import type { Tag, TagColorId } from '../types';
import { TAG_PALETTE } from '../types';

interface TagEditorProps {
  tags: Tag[];
  onChange: (next: Tag[]) => void;
}

/**
 * Staged rename/recolor/delete of board tags, mirroring CategoryEditor. Edits mutate the parent's
 * draft list and are persisted on the modal's Save. Tags are born from tasks (not created here), so
 * there's no "add" affordance — only management of existing ones.
 */
export function TagEditor({ tags, onChange }: TagEditorProps) {
  const [openPicker, setOpenPicker] = useState<string | null>(null);

  const update = (id: string, patch: Partial<Tag>) =>
    onChange(tags.map(t => t.id === id ? { ...t, ...patch } : t));

  const remove = (id: string) =>
    onChange(tags.filter(t => t.id !== id));

  if (tags.length === 0) {
    return <p className="settings-hint">No tags yet. Add a tag from any task and it'll show up here to rename, recolor, or remove.</p>;
  }

  return (
    <div className="cat-editor">
      {tags.map(tag => {
        const swatch = TAG_PALETTE.find(c => c.id === tag.colorId) ?? TAG_PALETTE[0];
        const picking = openPicker === tag.id;
        const count = tag.usageCount ?? 0;
        return (
          <div key={tag.id} className="cat-row">
            <button
              type="button"
              className="cat-row__swatch"
              style={{ background: swatch.text, borderColor: swatch.border }}
              onClick={() => setOpenPicker(picking ? null : tag.id)}
              aria-label={`Change color for ${tag.label}`}
              aria-expanded={picking}
            />
            <input
              className="cat-row__input"
              value={tag.label}
              maxLength={64}
              onChange={e => update(tag.id, { label: e.target.value })}
              placeholder="Tag label"
            />
            <span
              className="cat-row__count"
              title={count > 0 ? `On ${count} task${count === 1 ? '' : 's'}` : 'Unused'}
            >
              {count}
            </span>
            <button
              type="button"
              className="cat-row__del"
              onClick={() => remove(tag.id)}
              aria-label={`Delete ${tag.label}`}
              title={count > 0 ? `Delete and remove from ${count} task${count === 1 ? '' : 's'}` : 'Delete'}
            >
              ×
            </button>
            {picking && (
              <div className="cat-row__picker">
                <div className="color-swatches">
                  {TAG_PALETTE.map(c => (
                    <button
                      key={c.id}
                      type="button"
                      className={`color-swatch${tag.colorId === c.id ? ' color-swatch--selected' : ''}`}
                      style={{ background: c.text }}
                      onClick={() => { update(tag.id, { colorId: c.id as TagColorId }); setOpenPicker(null); }}
                      aria-label={c.id}
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

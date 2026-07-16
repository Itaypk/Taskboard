import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { PaperSwatchPicker } from './PaperSwatchPicker';
import { generateId } from '../utils';

interface CategoryEditorProps {
  categories: Category[];
  usage: Record<string, number>;
  onChange: (next: Category[]) => void;
}

export function CategoryEditor({ categories, usage, onChange }: CategoryEditorProps) {
  const { t } = useTranslation();
  const [openPicker, setOpenPicker] = useState<string | null>(null);

  const update = (id: string, patch: Partial<Category>) => {
    onChange(categories.map(c => c.id === id ? { ...c, ...patch } : c));
  };

  const remove = (id: string) => {
    if ((usage[id] ?? 0) > 0) return;
    onChange(categories.filter(c => c.id !== id));
  };

  const add = () => {
    const usedSwatches = new Set(categories.map(c => c.swatchId));
    const next = PAPER_SWATCHES.find(s => !usedSwatches.has(s.id))?.id ?? 'cream';
    onChange([
      ...categories,
      { id: generateId(), label: t('categoryEditor.newCategoryDefault'), swatchId: next as PaperSwatchId },
    ]);
  };

  return (
    <div className="cat-editor">
      {categories.map(cat => {
        const count = usage[cat.id] ?? 0;
        const swatch = PAPER_SWATCHES.find(s => s.id === cat.swatchId) ?? PAPER_SWATCHES[6];
        const picking = openPicker === cat.id;
        return (
          <div key={cat.id} className="cat-row">
            <button
              type="button"
              className="cat-row__swatch"
              style={{ background: swatch.paper, borderColor: swatch.edge }}
              onClick={() => setOpenPicker(picking ? null : cat.id)}
              aria-label={t('categoryEditor.changeColor', { label: cat.label })}
              aria-expanded={picking}
            />
            <input
              className="cat-row__input"
              value={cat.label}
              onChange={e => update(cat.id, { label: e.target.value })}
              placeholder={t('categoryEditor.namePlaceholder')}
            />
            <span className="cat-row__count">{count}</span>
            <button
              type="button"
              className="cat-row__del"
              disabled={count > 0}
              onClick={() => remove(cat.id)}
              aria-label={t('categoryEditor.delete', { label: cat.label })}
              title={count > 0 ? t('categoryEditor.inUseBy', { count }) : t('categoryEditor.deleteTitle')}
            >
              ×
            </button>
            {picking && (
              <div className="cat-row__picker">
                <PaperSwatchPicker
                  selected={cat.swatchId}
                  onSelect={id => { update(cat.id, { swatchId: id }); setOpenPicker(null); }}
                  size="sm"
                />
              </div>
            )}
          </div>
        );
      })}
      <button type="button" className="cat-row__add" onClick={add}>
        {t('categoryEditor.addCategory')}
      </button>
    </div>
  );
}

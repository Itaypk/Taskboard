import { useTranslation } from 'react-i18next';
import type { PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';

interface PaperSwatchPickerProps {
  selected: PaperSwatchId;
  onSelect: (id: PaperSwatchId) => void;
  size?: 'sm' | 'md';
  /** Only show these swatch ids (defaults to all). */
  only?: readonly PaperSwatchId[];
}

export function PaperSwatchPicker({ selected, onSelect, size = 'md', only }: PaperSwatchPickerProps) {
  const { t } = useTranslation();
  const swatches = only
    ? PAPER_SWATCHES.filter(s => only.includes(s.id))
    : PAPER_SWATCHES;

  return (
    <div className={`swatch-row swatch-row--${size}`} role="radiogroup" aria-label={t('paperSwatchPicker.ariaLabel')}>
      {swatches.map((s, i) => {
        const isSelected = s.id === selected;
        return (
          <button
            key={s.id}
            type="button"
            role="radio"
            aria-checked={isSelected}
            aria-label={s.id}
            className={`swatch${isSelected ? ' swatch--selected' : ''}`}
            style={{
              background: s.paper,
              borderColor: s.edge,
              ['--swatch-ink' as string]: s.ink,
              transform: `rotate(${((i % 2 === 0 ? -1 : 1) * (1.2 + (i * 0.4) % 2)).toFixed(2)}deg)`,
            }}
            onClick={() => onSelect(s.id)}
          />
        );
      })}
    </div>
  );
}

import type { Tag } from '../types';

interface WashiTapeProps {
  tag: Tag;
  index?: number;          // for stacking offset / rotation
  idSeed?: string;         // stable randomness per-tape (usually task id + tag index)
  onRemove?: () => void;
}

export function WashiTape({ tag, index = 0, idSeed = '', onRemove }: WashiTapeProps) {
  const jitterRot = jitter(idSeed + ':r:' + index) * 1.1;
  const jitterX   = jitter(idSeed + ':x:' + index) * 3;

  return (
    <span
      className="washi"
      data-color={tag.colorId}
      style={{
        transform: `translateX(${jitterX.toFixed(2)}px) rotate(${jitterRot.toFixed(2)}deg)`,
        zIndex: 10 + index,
      }}
    >
      <span className="washi__label">{tag.label}</span>
      {onRemove && (
        <button
          type="button"
          className="washi__remove"
          onClick={e => { e.stopPropagation(); onRemove(); }}
          aria-label={`Remove tag ${tag.label}`}
        >
          ×
        </button>
      )}
    </span>
  );
}

function jitter(seed: string): number {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  const n = (h >>> 0) / 0xffffffff;
  return n * 2 - 1;
}

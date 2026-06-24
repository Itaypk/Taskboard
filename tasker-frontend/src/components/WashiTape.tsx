import type { Tag } from '../types';

interface WashiTapeProps {
  tag: Tag;
  index?: number;          // for stacking offset / rotation
  idSeed?: string;         // stable randomness per-tape (usually task id + tag index)
  onRemove?: () => void;
  /** When set, the tape's label becomes a button (e.g. to open a tag editor). Kept separate from
   *  the × button so the two interactive targets don't nest. */
  onClick?: () => void;
}

export function WashiTape({ tag, index = 0, idSeed = '', onRemove, onClick }: WashiTapeProps) {
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
      {onClick ? (
        <button type="button" className="washi__label washi__label--btn" onClick={onClick} title={`Edit tag ${tag.label}`}>
          {tag.label}
        </button>
      ) : (
        <span className="washi__label">{tag.label}</span>
      )}
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

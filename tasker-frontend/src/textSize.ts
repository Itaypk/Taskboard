/**
 * Board text size: a per-device display preference (big on the laptop you screen-share from, normal
 * on your phone), so it lives in localStorage rather than in the account's settings.
 *
 * It is applied as CSS `zoom` on the board and the task drawer body (see `--board-zoom` in
 * index.css) rather than via the root font size: nearly every font size in the stylesheet is in px,
 * and `zoom` scales those, the spacing and the column widths together.
 */
export type TextSize = 'default' | 'large' | 'larger';

export const TEXT_SIZES: readonly TextSize[] = ['default', 'large', 'larger'];

const ZOOM: Record<TextSize, number> = { default: 1, large: 1.2, larger: 1.4 };

const STORAGE_KEY = 'backlog.textSize';

export function isTextSize(value: unknown): value is TextSize {
  return typeof value === 'string' && (TEXT_SIZES as readonly string[]).includes(value);
}

export function zoomFor(size: TextSize): number {
  return ZOOM[size];
}

export function loadTextSize(): TextSize {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return isTextSize(stored) ? stored : 'default';
  } catch {
    return 'default';
  }
}

export function saveTextSize(size: TextSize): void {
  try {
    localStorage.setItem(STORAGE_KEY, size);
  } catch {
    // Storage unavailable (private mode, blocked site data): the choice just won't survive a reload.
  }
}

/**
 * dnd-kit's translate for a card inside the zoomed board. dnd-kit measures in viewport pixels, but a
 * transform inside a `zoom`ed element is scaled by that zoom, so the card would outrun the pointer
 * (by 40% at the largest size). Dividing by the same factor keeps it under the cursor.
 */
export function boardTranslate({ x, y }: { x: number; y: number }): string {
  return `translate3d(calc(${x}px / var(--board-zoom, 1)), calc(${y}px / var(--board-zoom, 1)), 0)`;
}

import pineappleUrl from './assets/pineapple.webp';
import pineappleSmUrl from './assets/pineapple-sm.webp';
import mrRobotoUrl from './assets/mr_roboto.webp';
import mrRobotoSmUrl from './assets/mr_roboto-sm.webp';
import stationeryUrl from './assets/stationery_holder.webp';
import stationerySmUrl from './assets/stationery_holder-sm.webp';

/** A board mascot: the little character shown in the corner of the board. */
export interface Mascot {
  id: string;
  label: string;
  url: string;
  /** `srcset` for `url` — a `-sm` variant (tools/make-mascot-srcset.py) alongside the full asset.
   * Widths are baked in since Vite's `?w=` metadata isn't wired up here; keep them in sync with
   * that script's output if a mascot is ever re-cropped. */
  srcSet: string;
  /** `sizes` for `url`/`srcSet` — this mascot's own rendered width (not a shared approximation) at
   * each `.pineapple-pet` breakpoint (index.css: 420px desktop / 320px tablet / 220px mobile CSS
   * height x this image's own natural aspect ratio). Getting this right matters: `sizes` is what
   * the browser multiplies by device pixel ratio to decide whether the `-sm` candidate qualifies —
   * too generous a value (e.g. one shared across mascots of different aspect ratios) silently
   * disqualifies `-sm` on real devices and the srcset does nothing. */
  sizes: string;
}

/** Mascots in pick order. The first entry is the default (matches the backend's `BoardMascot.DEFAULT`). */
export const MASCOTS: Mascot[] = [
  {
    id: 'pineapple', label: 'Pineapple Pet', url: pineappleUrl,
    srcSet: `${pineappleSmUrl} 280w, ${pineappleUrl} 345w`,
    sizes: '(max-width: 600px) 103px, (max-width: 820px) 150px, 196px',
  },
  {
    id: 'mr_roboto', label: 'Mr. Roboto', url: mrRobotoUrl,
    srcSet: `${mrRobotoSmUrl} 316w, ${mrRobotoUrl} 479w`,
    sizes: '(max-width: 600px) 116px, (max-width: 820px) 169px, 221px',
  },
  {
    id: 'stationery', label: 'Pencil Cup', url: stationeryUrl,
    srcSet: `${stationerySmUrl} 318w, ${stationeryUrl} 704w`,
    sizes: '(max-width: 600px) 117px, (max-width: 820px) 170px, 223px',
  },
];

const DEFAULT_MASCOT = MASCOTS[0];

/** Resolves a mascot id to its definition, falling back to the default for unknown/missing ids. */
export function mascotFor(id: string | null | undefined): Mascot {
  return MASCOTS.find(m => m.id === id) ?? DEFAULT_MASCOT;
}

import pineappleUrl from './assets/pineapple.webp';
import mrRobotoUrl from './assets/mr_roboto.webp';
import stationeryUrl from './assets/stationery_holder.webp';

/** A board mascot: the little character shown in the corner of the board. */
export interface Mascot {
  id: string;
  label: string;
  url: string;
}

/** Mascots in pick order. The first entry is the default (matches the backend's `BoardMascot.DEFAULT`). */
export const MASCOTS: Mascot[] = [
  { id: 'pineapple', label: 'Pineapple Pet', url: pineappleUrl },
  { id: 'mr_roboto', label: 'Mr. Roboto', url: mrRobotoUrl },
  { id: 'stationery', label: 'Pencil Cup', url: stationeryUrl },
];

const DEFAULT_MASCOT = MASCOTS[0];

/** Resolves a mascot id to its definition, falling back to the default for unknown/missing ids. */
export function mascotFor(id: string | null | undefined): Mascot {
  return MASCOTS.find(m => m.id === id) ?? DEFAULT_MASCOT;
}

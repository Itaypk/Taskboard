import type { Task, Priority } from './types';

/**
 * How the board is ordered. `none` means no auto-sort — the hand-curated, server-persisted order
 * (the task `sortKey`). The field modes are non-destructive display transforms layered on top of it;
 * they never touch `sortKey`, so switching back to `none` restores the exact manual order.
 */
export type SortMode = 'none' | 'priority' | 'deadline' | 'created' | 'title' | 'longest' | 'shortest';

/** A field sort — every mode except the manual `none`. */
export type SortField = Exclude<SortMode, 'none'>;

export interface SortOption {
  id: SortField;
  label: string;
}

/** Field sorts offered in the menu (display order). `none` is presented separately as the reset. */
export const SORT_OPTIONS: readonly SortOption[] = [
  { id: 'deadline', label: 'Deadline' },
  { id: 'priority', label: 'Priority' },
  { id: 'created',  label: 'Recently added' },
  { id: 'title',    label: 'A–Z' },
  { id: 'longest',  label: 'Longest' },
  { id: 'shortest', label: 'Shortest' },
];

/**
 * Default precedence of the tiebreaker keys. Every field sort applies *all* of these after its lead
 * key — the user's choice only decides which one leads; the rest follow in this order, then the
 * manual `sortKey` breaks any final tie. Sorting on several keys at once makes the result both
 * stabler and more meaningful (e.g. "by deadline" still orders same-day tasks by priority instead of
 * leaving them arbitrary). The duration sorts (`longest`/`shortest`) are deliberately absent: they're
 * two directions of one field, so they only ever lead and never serve as a shared tiebreaker.
 */
const KEY_PRECEDENCE: readonly SortField[] = ['priority', 'deadline', 'created', 'title'];

const ALL_MODES: readonly SortMode[] = ['none', ...KEY_PRECEDENCE, 'longest', 'shortest'];

export function isSortMode(value: string | null): value is SortMode {
  return value != null && (ALL_MODES as readonly string[]).includes(value);
}

const PRIORITY_RANK: Record<Priority, number> = { high: 0, medium: 1, low: 2 };

type Comparator = (a: Task, b: Task) => number;

/** The manual order — the baseline every field sort falls back to on a full tie. */
function byCustom(a: Task, b: Task): number {
  return a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0;
}

/**
 * Missing values (no deadline, no priority) always sort last, whichever direction the field
 * sorts. Two present values defer to `cmp`; two missing values tie (→ the next key decides).
 */
function nullsLast<T>(a: T | undefined | null, b: T | undefined | null, cmp: (x: T, y: T) => number): number {
  const aMissing = a == null || a === '';
  const bMissing = b == null || b === '';
  if (aMissing && bMissing) return 0;
  if (aMissing) return 1;
  if (bMissing) return -1;
  return cmp(a as T, b as T);
}

const KEY_COMPARATORS: Record<SortField, Comparator> = {
  priority: (a, b) => nullsLast(a.priority, b.priority, (x, y) => PRIORITY_RANK[x] - PRIORITY_RANK[y]),
  // Soonest first; ISO YYYY-MM-DD sorts chronologically as plain strings.
  deadline: (a, b) => nullsLast(a.deadline, b.deadline, (x, y) => x.localeCompare(y)),
  // Newest first.
  created: (a, b) => b.createdAt.localeCompare(a.createdAt),
  title: (a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }),
  // By estimated duration; tasks with no estimate sort last either way (nullsLast).
  longest: (a, b) => nullsLast(a.estimatedMinutes, b.estimatedMinutes, (x, y) => y - x),
  shortest: (a, b) => nullsLast(a.estimatedMinutes, b.estimatedMinutes, (x, y) => x - y),
};

function comparatorFor(mode: SortMode): Comparator {
  if (mode === 'none') return byCustom;
  // Promote the chosen key to the front; the rest keep their default precedence.
  const keys: SortField[] = [mode, ...KEY_PRECEDENCE.filter(k => k !== mode)];
  const comparators = keys.map(k => KEY_COMPARATORS[k]);
  return (a, b) => {
    for (const cmp of comparators) {
      const r = cmp(a, b);
      if (r !== 0) return r;
    }
    return byCustom(a, b);
  };
}

/** Returns a new array of `tasks` ordered for the given mode (input is not mutated). */
export function sortTasks(tasks: Task[], mode: SortMode): Task[] {
  return [...tasks].sort(comparatorFor(mode));
}

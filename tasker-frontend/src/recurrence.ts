import i18n from './i18n';
import { formatDate } from './i18n/format';
import type { Recurrence, RecurrenceKind } from './types';

/**
 * Client-side helpers for recurring tasks. The server owns the real roll-forward
 * (`RecurrenceCalculator`); these only suggest a first "Next on" date in the drawer, validate before
 * saving, and render labels. Dates are `YYYY-MM-DD` strings, and all math runs in UTC so a DST
 * change can't shift a day.
 */

export const RECURRENCE_KINDS: RecurrenceKind[] = ['EVERY_N_DAYS', 'EVERY_N_MONTHS', 'WEEKLY', 'MONTHLY', 'YEARLY'];

const pad = (n: number) => String(n).padStart(2, '0');

const toIso = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;

const fromIso = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};

const daysInMonth = (year: number, month: number) => new Date(Date.UTC(year, month, 0)).getUTCDate();

/** Day 31 means "last day of the month"; Feb 29 lands on Feb 28 in non-leap years. */
const clampedDate = (year: number, month: number, day: number) =>
  new Date(Date.UTC(year, month - 1, Math.min(day, daysInMonth(year, month))));

/**
 * Today on the browser's calendar. The server filters on the user's settings timezone; for hiding a
 * card the moment it rolls forward, the browser's zone is a close enough stand-in.
 */
export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function isFutureDate(iso: string | null | undefined, today: string = todayIso()): boolean {
  return !!iso && iso > today;
}

/** The next occurrence of a recurring task that isn't due yet, or null when it's due (or not recurring). */
export function waitingUntil(task: { recurrence?: Recurrence | null; relevantFrom?: string }, today: string = todayIso()): string | null {
  return task.recurrence && isFutureDate(task.relevantFrom, today) ? task.relevantFrom! : null;
}

/** A starting rule for a kind, seeded from today so calendar kinds land on a sensible first date. */
export function defaultRule(kind: RecurrenceKind, today: string): Recurrence {
  const d = fromIso(today);
  switch (kind) {
    case 'EVERY_N_DAYS': return { kind, every: 7 };
    case 'EVERY_N_MONTHS': return { kind, every: 1 };
    case 'WEEKLY': return { kind, day: ((d.getUTCDay() + 6) % 7) + 1 };
    case 'MONTHLY': return { kind, day: d.getUTCDate() };
    case 'YEARLY': return { kind, month: d.getUTCMonth() + 1, day: d.getUTCDate() };
  }
}

/** Mirrors `RecurrenceCalculator.firstOccurrence`: today for interval rules, the next match otherwise. */
export function firstOccurrence(rule: Recurrence, today: string): string {
  const d = fromIso(today);
  switch (rule.kind) {
    case 'EVERY_N_DAYS':
    case 'EVERY_N_MONTHS':
      return today;
    case 'WEEKLY': {
      const isoWeekday = ((d.getUTCDay() + 6) % 7) + 1;
      const ahead = ((rule.day ?? isoWeekday) - isoWeekday + 7) % 7;
      return toIso(new Date(d.getTime() + ahead * 86_400_000));
    }
    case 'MONTHLY': {
      const day = rule.day ?? 1;
      const year = d.getUTCFullYear();
      const month = d.getUTCMonth() + 1;
      const thisMonth = clampedDate(year, month, day);
      if (thisMonth >= d) return toIso(thisMonth);
      return toIso(month === 12 ? clampedDate(year + 1, 1, day) : clampedDate(year, month + 1, day));
    }
    case 'YEARLY': {
      const month = rule.month ?? 1;
      const day = rule.day ?? 1;
      const thisYear = clampedDate(d.getUTCFullYear(), month, day);
      return toIso(thisYear >= d ? thisYear : clampedDate(d.getUTCFullYear() + 1, month, day));
    }
  }
}

const inRange = (v: number | null | undefined, min: number, max: number) =>
  v != null && Number.isInteger(v) && v >= min && v <= max;

/** Mirrors the server's `TaskRecurrence.validationError` ranges so a bad rule is caught before saving. */
export function isRuleValid(rule: Recurrence): boolean {
  if (rule.dueWithinDays != null && !inRange(rule.dueWithinDays, 0, 365)) return false;
  switch (rule.kind) {
    case 'EVERY_N_DAYS': return inRange(rule.every, 1, 3650);
    case 'EVERY_N_MONTHS': return inRange(rule.every, 1, 120);
    case 'WEEKLY': return inRange(rule.day, 1, 7);
    case 'MONTHLY': return inRange(rule.day, 1, 31);
    case 'YEARLY': return inRange(rule.month, 1, 12) && inRange(rule.day, 1, daysInMonth(2024, rule.month!));
  }
}

/**
 * The wire shape: only the fields the kind takes. The server rejects stray fields, so a rule that was
 * edited across kinds must not carry leftovers.
 */
export function normalizeRule(rule: Recurrence): Recurrence {
  const dueWithinDays = rule.dueWithinDays ?? null;
  switch (rule.kind) {
    case 'EVERY_N_DAYS':
    case 'EVERY_N_MONTHS':
      return { kind: rule.kind, every: rule.every ?? null, day: null, month: null, dueWithinDays };
    case 'WEEKLY':
    case 'MONTHLY':
      return { kind: rule.kind, every: null, day: rule.day ?? null, month: null, dueWithinDays };
    case 'YEARLY':
      return { kind: rule.kind, every: null, day: rule.day ?? null, month: rule.month ?? null, dueWithinDays };
  }
}

export function daysInMonthOfYear(month: number): number {
  // 2024 is a leap year, so Feb offers 29 — the rule clamps it in other years.
  return daysInMonth(2024, month);
}

/** e.g. "Sep 25" in the active locale. */
export function formatShortDate(iso: string): string {
  return formatDate(fromIso(iso), { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

export function weekdayName(isoWeekday: number): string {
  // 2024-01-01 was a Monday.
  return formatDate(new Date(Date.UTC(2024, 0, isoWeekday)), { weekday: 'long', timeZone: 'UTC' });
}

export function monthName(month: number): string {
  return formatDate(new Date(Date.UTC(2024, month - 1, 1)), { month: 'long', timeZone: 'UTC' });
}

function ruleText(rule: Recurrence): string {
  switch (rule.kind) {
    case 'EVERY_N_DAYS': return i18n.t('recurrence.summaryEveryDays', { count: rule.every ?? 0 });
    case 'EVERY_N_MONTHS': return i18n.t('recurrence.summaryEveryMonths', { count: rule.every ?? 0 });
    case 'WEEKLY': return i18n.t('recurrence.summaryWeekly', { weekday: weekdayName(rule.day ?? 1) });
    case 'MONTHLY':
      return rule.day === 31
        ? i18n.t('recurrence.summaryMonthlyLastDay')
        : i18n.t('recurrence.summaryMonthly', { day: rule.day ?? 1 });
    case 'YEARLY':
      return i18n.t('recurrence.summaryYearly', {
        date: formatDate(new Date(Date.UTC(2024, (rule.month ?? 1) - 1, rule.day ?? 1)), { month: 'long', day: 'numeric', timeZone: 'UTC' }),
      });
  }
}

/** "Repeats 6 months after it's done · next Sep 10 · due within 30 days" */
export function recurrenceSummary(rule: Recurrence, nextOn?: string): string {
  const parts = [ruleText(rule)];
  if (nextOn) parts.push(i18n.t('recurrence.summaryNext', { date: formatShortDate(nextOn) }));
  if (rule.dueWithinDays != null) parts.push(i18n.t('recurrence.summaryDue', { count: rule.dueWithinDays }));
  return parts.join(' · ');
}

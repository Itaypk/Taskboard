import { describe, expect, it } from 'vitest';
import { defaultRule, firstOccurrence, isFutureDate, isRuleValid, normalizeRule } from './recurrence';

// 2026-09-13 is a Sunday.
const today = '2026-09-13';

describe('firstOccurrence', () => {
  it('is today for interval rules', () => {
    expect(firstOccurrence({ kind: 'EVERY_N_DAYS', every: 45 }, today)).toBe(today);
    expect(firstOccurrence({ kind: 'EVERY_N_MONTHS', every: 6 }, today)).toBe(today);
  });

  it('finds the next matching day of month, rolling into next month and year', () => {
    expect(firstOccurrence({ kind: 'MONTHLY', day: 25 }, today)).toBe('2026-09-25');
    expect(firstOccurrence({ kind: 'MONTHLY', day: 13 }, today)).toBe(today);
    expect(firstOccurrence({ kind: 'MONTHLY', day: 5 }, today)).toBe('2026-10-05');
    expect(firstOccurrence({ kind: 'MONTHLY', day: 5 }, '2026-12-20')).toBe('2027-01-05');
  });

  it('treats day 31 as the last day of the month', () => {
    expect(firstOccurrence({ kind: 'MONTHLY', day: 31 }, '2027-02-10')).toBe('2027-02-28');
  });

  it('finds the next matching weekday', () => {
    expect(firstOccurrence({ kind: 'WEEKLY', day: 2 }, today)).toBe('2026-09-15');
    expect(firstOccurrence({ kind: 'WEEKLY', day: 7 }, today)).toBe(today);
  });

  it('finds the next yearly date and clamps leap day', () => {
    expect(firstOccurrence({ kind: 'YEARLY', month: 3, day: 14 }, today)).toBe('2027-03-14');
    expect(firstOccurrence({ kind: 'YEARLY', month: 12, day: 1 }, today)).toBe('2026-12-01');
    expect(firstOccurrence({ kind: 'YEARLY', month: 2, day: 29 }, '2027-01-01')).toBe('2027-02-28');
  });
});

describe('defaultRule', () => {
  it('seeds calendar kinds from today', () => {
    expect(defaultRule('WEEKLY', today)).toEqual({ kind: 'WEEKLY', day: 7 });
    expect(defaultRule('MONTHLY', today)).toEqual({ kind: 'MONTHLY', day: 13 });
    expect(defaultRule('YEARLY', today)).toEqual({ kind: 'YEARLY', month: 9, day: 13 });
  });
});

describe('isRuleValid', () => {
  it('accepts well-formed rules', () => {
    expect(isRuleValid({ kind: 'EVERY_N_DAYS', every: 1 })).toBe(true);
    expect(isRuleValid({ kind: 'MONTHLY', day: 31, dueWithinDays: 0 })).toBe(true);
    expect(isRuleValid({ kind: 'YEARLY', month: 2, day: 29 })).toBe(true);
  });

  it('rejects missing and out-of-range values', () => {
    expect(isRuleValid({ kind: 'EVERY_N_DAYS', every: null })).toBe(false);
    expect(isRuleValid({ kind: 'EVERY_N_MONTHS', every: 121 })).toBe(false);
    expect(isRuleValid({ kind: 'YEARLY', month: 4, day: 31 })).toBe(false);
    expect(isRuleValid({ kind: 'WEEKLY', day: 2, dueWithinDays: 400 })).toBe(false);
  });
});

describe('normalizeRule', () => {
  it('drops fields the kind does not take, since the server rejects them', () => {
    expect(normalizeRule({ kind: 'MONTHLY', every: 6, day: 25, month: 3, dueWithinDays: 7 }))
      .toEqual({ kind: 'MONTHLY', every: null, day: 25, month: null, dueWithinDays: 7 });
  });
});

describe('isFutureDate', () => {
  it('compares calendar days', () => {
    expect(isFutureDate('2026-09-14', today)).toBe(true);
    expect(isFutureDate(today, today)).toBe(false);
    expect(isFutureDate(undefined, today)).toBe(false);
  });
});

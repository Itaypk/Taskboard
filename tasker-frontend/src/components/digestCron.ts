// The daily digest's schedule is a Spring cron restricted to "0 <minute> <hour> * * <days>"
// (at most once a day; the backend rejects anything else). See docs/DAILY-DIGEST.md.

/** Cron day codes in week order. */
export const DIGEST_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;
export type DigestDay = (typeof DIGEST_DAYS)[number];

export const DEFAULT_DIGEST_CRON = '0 0 8 * * *';

export interface DigestSchedule {
  /** Selected days, in week order. */
  days: DigestDay[];
  /** "HH:mm" */
  time: string;
}

const DEFAULT_SCHEDULE: DigestSchedule = { days: [...DIGEST_DAYS], time: '08:00' };

function isDigestDay(value: string): value is DigestDay {
  return (DIGEST_DAYS as readonly string[]).includes(value);
}

/** Expands "*", lists and ranges ("MON-FRI,SUN") into individual days; null if unrecognized. */
function parseDays(field: string): DigestDay[] | null {
  if (field === '*') return [...DIGEST_DAYS];
  const selected = new Set<DigestDay>();
  for (const part of field.toUpperCase().split(',')) {
    const [from, to] = part.split('-');
    if (!isDigestDay(from) || (to !== undefined && !isDigestDay(to))) return null;
    const start = DIGEST_DAYS.indexOf(from);
    const end = to === undefined ? start : DIGEST_DAYS.indexOf(to);
    if (end < start) return null;
    DIGEST_DAYS.slice(start, end + 1).forEach(d => selected.add(d));
  }
  return DIGEST_DAYS.filter(d => selected.has(d));
}

export function parseDigestCron(cron: string | null | undefined): DigestSchedule {
  const parts = (cron ?? '').trim().split(/\s+/);
  if (parts.length !== 6) return DEFAULT_SCHEDULE;
  const [, minute, hour, , , dow] = parts;
  const h = Number(hour);
  const m = Number(minute);
  const days = parseDays(dow);
  if (Number.isNaN(h) || Number.isNaN(m) || days === null || days.length === 0) return DEFAULT_SCHEDULE;
  return { days, time: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}` };
}

/** Composes the cron; every day becomes "*". Expects at least one day. */
export function composeDigestCron({ days, time }: DigestSchedule): string {
  const [hh, mm] = time.split(':');
  const dayField = days.length === DIGEST_DAYS.length
    ? '*'
    : DIGEST_DAYS.filter(d => days.includes(d)).join(',');
  return `0 ${Number(mm)} ${Number(hh)} * * ${dayField}`;
}

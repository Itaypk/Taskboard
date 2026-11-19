import { DIGEST_DAYS, type DigestDay } from '../digestCron';

/** Week days with the two spellings settings use: the `weekStartDay` value and the cron day code. */
export const DAY_VALUES: { value: string; cron: string; labelKey: string }[] = [
  { value: 'MONDAY', cron: 'MON', labelKey: 'settingsModal.days.monday' },
  { value: 'TUESDAY', cron: 'TUE', labelKey: 'settingsModal.days.tuesday' },
  { value: 'WEDNESDAY', cron: 'WED', labelKey: 'settingsModal.days.wednesday' },
  { value: 'THURSDAY', cron: 'THU', labelKey: 'settingsModal.days.thursday' },
  { value: 'FRIDAY', cron: 'FRI', labelKey: 'settingsModal.days.friday' },
  { value: 'SATURDAY', cron: 'SAT', labelKey: 'settingsModal.days.saturday' },
  { value: 'SUNDAY', cron: 'SUN', labelKey: 'settingsModal.days.sunday' },
];

// Spring CronExpression: "second minute hour day-of-month month day-of-week"
export function composePlanningCron(day: string, time: string): string | null {
  if (!day || !time) return null;
  const [hh, mm] = time.split(':');
  if (hh == null || mm == null) return null;
  return `0 ${Number(mm)} ${Number(hh)} * * ${day}`;
}

/** The weekly planning schedule; `day` is a cron day code, or '' when planning is off. */
export function parsePlanningCron(cron: string | null | undefined): { day: string; time: string } {
  if (!cron) return { day: '', time: '09:00' };
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6) return { day: '', time: '09:00' };
  const [, minute, hour, , , dow] = parts;
  const h = Number(hour);
  const m = Number(minute);
  if (Number.isNaN(h) || Number.isNaN(m)) return { day: '', time: '09:00' };
  const time = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  return { day: dow.toUpperCase(), time };
}

/** Digest day chips in the user's week order, starting from their configured week-start day. */
export function digestDaysInWeekOrder(weekStartDay: string | null | undefined): DigestDay[] {
  const start = DAY_VALUES.findIndex(d => d.value === weekStartDay);
  if (start <= 0) return [...DIGEST_DAYS];
  return [...DIGEST_DAYS.slice(start), ...DIGEST_DAYS.slice(0, start)];
}

/** Short localized weekday name ("Mon", "ב׳", …). 2024-01-01 was a Monday. */
export function shortDayName(day: DigestDay, language: string): string {
  const date = new Date(Date.UTC(2024, 0, 1 + DIGEST_DAYS.indexOf(day)));
  return new Intl.DateTimeFormat(language, { weekday: 'short', timeZone: 'UTC' }).format(date);
}

/** Localized "HH:mm" for display, in the locale's own clock style ("18:00", "6:00 PM"). */
export function formatClockTime(time: string, language: string): string {
  const [h, m] = time.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return time;
  return new Intl.DateTimeFormat(language, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' })
    .format(new Date(Date.UTC(2024, 0, 1, h, m)));
}

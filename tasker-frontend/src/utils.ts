import i18n from './i18n';
import { formatDate, formatTime } from './i18n/format';

export function formatDeadline(isoDate: string): string {
  const d = new Date(isoDate + 'T00:00:00');
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000);

  if (diff < 0) return i18n.t('utils.overdue', { count: Math.abs(diff) });
  if (diff === 0) return i18n.t('utils.today');
  if (diff === 1) return i18n.t('utils.tomorrow');
  if (diff <= 6) return formatDate(d, { weekday: 'short' });
  return formatDate(d, { month: 'short', day: 'numeric' });
}

export function isOverdue(isoDate: string): boolean {
  const d = new Date(isoDate + 'T00:00:00');
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return d < today;
}

export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes}m`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h}h ${m}m` : `${h}h`;
}

export function generateId(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/** Formats a count with a singular/plural noun, e.g. `plural(1, 'task', 'tasks') === '1 task'`. */
export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/**
 * Stable per-id rotation in [-MAX, +MAX] degrees.
 * Used to give every post-it a slightly different tilt that doesn't jitter on re-render.
 */
export function rotationFromId(id: string, max = 2.6): number {
  const n = hash(id) / 0xffffffff;
  return (n * 2 - 1) * max;
}

/**
 * One-line plain-text teaser of a markdown note, used for the compact list's preview row.
 * Deliberately crude — strips the common markdown markers rather than parsing, since the result
 * is a single truncated line, not rendered content.
 */
export function notePreview(markdown: string): string {
  return markdown
    .replace(/```[\s\S]*?```/g, ' ')            // fenced code blocks
    .replace(/`([^`]+)`/g, '$1')                // inline code
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')      // images
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')    // links → their text
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')         // headings
    .replace(/^\s*>\s?/gm, '')                  // blockquotes
    .replace(/^\s*[-*+]\s+/gm, '')              // bullet markers
    .replace(/^\s*\d+\.\s+/gm, '')              // ordered markers
    .replace(/[*_~#]/g, '')                     // stray emphasis/heading marks
    .replace(/\s+/g, ' ')
    .trim();
}

export function formatRelative(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const diffMs = Date.now() - date.getTime();
  const diffHours = Math.round(diffMs / (1000 * 60 * 60));
  if (diffHours < 1) return i18n.t('utils.justNow');
  if (diffHours < 24) return i18n.t('utils.hoursAgo', { count: diffHours });
  const diffDays = Math.round(diffHours / 24);
  if (diffDays < 7) return i18n.t('utils.daysAgo', { count: diffDays });
  return formatDate(date, { month: 'short', day: 'numeric' });
}

export function formatTimeSlot(startIso: string, endIso: string): string {
  const start = new Date(startIso);
  const end = new Date(endIso);
  if (Number.isNaN(start.getTime())) return '';
  // hour12 is pinned to 24h for now; see the D4 open question in i18n/format.ts.
  const day = formatDate(start, { weekday: 'short' });
  const startTime = formatTime(start, { hour: '2-digit', minute: '2-digit', hour12: false });
  if (Number.isNaN(end.getTime())) return `${day} ${startTime}`;
  const endTime = formatTime(end, { hour: '2-digit', minute: '2-digit', hour12: false });
  return `${day} ${startTime}–${endTime}`;
}

export function jitterFromId(id: string, seedOffset: number, range: number): number {
  const n = hash(id + ':' + seedOffset) / 0xffffffff;
  return (n * 2 - 1) * range;
}

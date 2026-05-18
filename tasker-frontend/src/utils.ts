export function formatDeadline(isoDate: string): string {
  const d = new Date(isoDate + 'T00:00:00');
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000);

  if (diff < 0) return `${Math.abs(diff)}d overdue`;
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff <= 6) return d.toLocaleDateString('en', { weekday: 'short' });
  return d.toLocaleDateString('en', { month: 'short', day: 'numeric' });
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

export function jitterFromId(id: string, seedOffset: number, range: number): number {
  const n = hash(id + ':' + seedOffset) / 0xffffffff;
  return (n * 2 - 1) * range;
}

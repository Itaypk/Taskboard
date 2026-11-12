/** "14 minutes ago", in the UI language. */
export function formatRelative(iso: string, language: string, now: number = Date.now()): string | null {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return null;
    const seconds = Math.round((then - now) / 1000);
    const format = new Intl.RelativeTimeFormat(language, { numeric: 'auto' });
    const abs = Math.abs(seconds);
    if (abs < 60) return format.format(seconds, 'second');
    if (abs < 3600) return format.format(Math.round(seconds / 60), 'minute');
    if (abs < 86400) return format.format(Math.round(seconds / 3600), 'hour');
    return format.format(Math.round(seconds / 86400), 'day');
}

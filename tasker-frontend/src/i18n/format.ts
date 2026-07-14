/**
 * Locale-aware date/time formatting, routed through the active full locale tag (docs/I18N.md, D4).
 *
 * The "active locale" is the user's full preference tag (`en-US`, `en-GB`, `he`, …) — distinct from
 * the i18next *UI language* (`en`), because the region subtag still drives `Intl` even when a single
 * `en` catalog serves both English variants. It is set on auth bootstrap and on a settings language
 * change (see `./index.ts`), and defaults to `en-US` before either runs.
 *
 * Open question (D4): planner time slots are still forced to 24h (`hour12: false`) at the call sites,
 * preserving today's behavior. Whether to instead let the locale decide (en-US → 12h; he/ru/ar are
 * 24h natively) is a deliberate, deferred product decision — flipping it is a visible change.
 */

const FALLBACK_LOCALE = 'en-US';

let activeLocale = FALLBACK_LOCALE;

/** Sets the locale tag all formatting helpers use by default. Idempotent; blank falls back to en-US. */
export function setActiveLocale(tag: string | null | undefined): void {
    activeLocale = tag && tag.trim().length > 0 ? tag : FALLBACK_LOCALE;
}

/** The active full locale tag (e.g. `en-US`). Pass to `toLocale*` for ad-hoc formatting. */
export function getActiveLocale(): string {
    return activeLocale;
}

/** `Date#toLocaleDateString` bound to the active locale. Accepts a `Date` or an ISO string. */
export function formatDate(date: Date | string, options?: Intl.DateTimeFormatOptions, locale: string = activeLocale): string {
    const d = typeof date === 'string' ? new Date(date) : date;
    return d.toLocaleDateString(locale, options);
}

/** `Date#toLocaleTimeString` bound to the active locale. Accepts a `Date` or an ISO string. */
export function formatTime(date: Date | string, options?: Intl.DateTimeFormatOptions, locale: string = activeLocale): string {
    const d = typeof date === 'string' ? new Date(date) : date;
    return d.toLocaleTimeString(locale, options);
}

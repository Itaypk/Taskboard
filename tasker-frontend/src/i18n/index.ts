/**
 * i18next setup for the web UI (docs/I18N.md, D3/D7). English is bundled eagerly (it is the
 * fallback and always needed); other languages load as lazy chunks when they launch.
 *
 * Phase 1 note: only `en` is *launched*, so `resolveUiLanguage` maps every preference to `en` for
 * catalog purposes — the UI stays English while comms keep using the stored preference (D7). The
 * full preference tag still flows to `Intl` via `setActiveLocale`, and `<html lang/dir>` is set
 * from the resolved language so RTL wiring is in place ahead of the Hebrew launch.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import enTranslation from '../locales/en/translation.json';
import { setActiveLocale } from './format';

/** Languages whose UI catalog is complete and QA'd. Add `he`, `ru`, `ar` as each ships (D7). */
export const LAUNCHED_UI_LANGUAGES = ['en'] as const;

/** All languages the picker may offer; matches the backend's trimmed supported list (D1). */
export const SUPPORTED_UI_LANGUAGES = ['en', 'he', 'ru', 'ar'] as const;
export type UiLanguage = (typeof SUPPORTED_UI_LANGUAGES)[number];

const RTL_UI_LANGUAGES: readonly string[] = ['he', 'ar'];
const LOCALE_CACHE_KEY = 'backlog.locale';
const FALLBACK_LOCALE = 'en-US';

/** Lazy loaders for non-English catalogs, wired up as languages launch. */
const catalogLoaders: Partial<Record<UiLanguage, () => Promise<{ default: Record<string, unknown> }>>> = {
    // he: () => import('../locales/he/translation.json'),
    // ru: () => import('../locales/ru/translation.json'),
    // ar: () => import('../locales/ar/translation.json'),
};

function baseLanguage(tag: string): string {
    return tag.toLowerCase().split('-')[0];
}

/** The launched UI language for a preference/browser tag; unlaunched languages degrade to `en` (D7). */
export function resolveUiLanguage(tag?: string | null): UiLanguage {
    const base = baseLanguage(tag ?? '');
    return (LAUNCHED_UI_LANGUAGES as readonly string[]).includes(base) ? (base as UiLanguage) : 'en';
}

function readCachedLocale(): string | null {
    try {
        return window.localStorage.getItem(LOCALE_CACHE_KEY);
    } catch {
        return null;
    }
}

function cacheLocale(tag: string): void {
    try {
        window.localStorage.setItem(LOCALE_CACHE_KEY, tag);
    } catch {
        /* private mode / storage disabled — detection just falls back to the browser next boot */
    }
}

/**
 * Best full preference tag before `/me` resolves: the cached last-resolved locale (returning users,
 * avoids a language flash), else the first `navigator.languages` entry matching the supported list,
 * else `en-US`.
 */
function detectPreferredTag(): string {
    const cached = readCachedLocale();
    if (cached) return cached;
    const candidates = navigator.languages ?? [navigator.language];
    for (const c of candidates) {
        if (c && (SUPPORTED_UI_LANGUAGES as readonly string[]).includes(baseLanguage(c))) return c;
    }
    return FALLBACK_LOCALE;
}

function applyDocumentLanguage(ui: UiLanguage, fullTag: string): void {
    const el = document.documentElement;
    el.lang = fullTag;
    el.dir = RTL_UI_LANGUAGES.includes(ui) ? 'rtl' : 'ltr';
}

async function loadCatalog(ui: UiLanguage): Promise<void> {
    if (ui === 'en' || i18n.hasResourceBundle(ui, 'translation')) return;
    const loader = catalogLoaders[ui];
    if (!loader) return; // not yet launched — fallbackLng handles it
    const mod = await loader();
    i18n.addResourceBundle(ui, 'translation', mod.default, true, true);
}

const bootTag = detectPreferredTag();

i18n.use(initReactI18next).init({
    resources: { en: { translation: enTranslation } },
    lng: resolveUiLanguage(bootTag),
    fallbackLng: 'en',
    interpolation: { escapeValue: false }, // React already escapes
    returnNull: false,
    saveMissing: import.meta.env.DEV,
    // Never surface raw keys in production: the complete `en` catalog + fallbackLng guarantees a
    // value; in dev we log misses so extraction gaps are caught during review.
    missingKeyHandler: import.meta.env.DEV
        ? (_lngs, ns, key) => console.warn(`[i18n] missing key: ${ns}:${key}`)
        : undefined,
    react: { useSuspense: false },
});

// Apply the boot locale to Intl + <html> immediately so first paint matches (no flash).
setActiveLocale(bootTag);
applyDocumentLanguage(resolveUiLanguage(bootTag), bootTag);

/**
 * Switches the active locale everywhere: loads the catalog if needed, flips i18next's UI language,
 * points `Intl` at the full tag, updates `<html lang/dir>`, and caches the tag for the next boot.
 * Called with the stored preference on auth bootstrap and with the new value on a settings change.
 */
export async function applyLocale(tag?: string | null): Promise<void> {
    const fullTag = tag && tag.trim().length > 0 ? tag : detectPreferredTag();
    const ui = resolveUiLanguage(fullTag);
    await loadCatalog(ui);
    if (i18n.language !== ui) await i18n.changeLanguage(ui);
    setActiveLocale(fullTag);
    applyDocumentLanguage(ui, fullTag);
    cacheLocale(fullTag);
}

export default i18n;

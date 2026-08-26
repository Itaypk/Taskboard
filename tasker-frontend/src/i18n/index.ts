/**
 * i18next setup for the web UI (docs/I18N.md, D3/D7). English is bundled eagerly (it is the
 * fallback and always needed); other languages load as lazy chunks when they launch.
 *
 * Launched languages (`LAUNCHED_UI_LANGUAGES`) render in their own catalog; every other preference
 * degrades to `en` for catalog purposes while comms keep using the stored preference (D7). The full
 * preference tag still flows to `Intl` via `setActiveLocale`, and `<html lang/dir>` is set from the
 * *resolved* language, so a `ru` preference reads English text with `lang="en"` rather than lying.
 *
 * `?uiLang=<code>` forces an unlaunched language in dev builds only, so its catalog and direction
 * can be exercised before launch. See `devPreviewLanguage`.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import enTranslation from '../locales/en/translation.json';
import { isUsableLocale, setActiveLocale } from './format';

/** Languages whose UI catalog is complete and QA'd. Add `ru`, `ar` as each ships (D7). */
export const LAUNCHED_UI_LANGUAGES = ['en', 'he'] as const;

/** All languages the picker may offer; matches the backend's trimmed supported list (D1). */
export const SUPPORTED_UI_LANGUAGES = ['en', 'he', 'ru', 'ar'] as const;
export type UiLanguage = (typeof SUPPORTED_UI_LANGUAGES)[number];

const RTL_UI_LANGUAGES: readonly string[] = ['he', 'ar'];
const LOCALE_CACHE_KEY = 'backlog.locale';
const FALLBACK_LOCALE = 'en-US';

/** Lazy loaders for non-English catalogs, wired up as languages launch. */
const catalogLoaders: Partial<Record<UiLanguage, () => Promise<{ default: Record<string, unknown> }>>> = {
    he: () => import('../locales/he/translation.json'),
    // ru: () => import('../locales/ru/translation.json'),
    // ar: () => import('../locales/ar/translation.json'),
};

function baseLanguage(tag: string): string {
    return tag.toLowerCase().split('-')[0];
}

/**
 * Dev-only preview override: `?uiLang=he` forces an unlaunched language (and, for `he`/`ar`, RTL)
 * so the direction pass can be exercised before the catalog is complete. It is read from the URL
 * on every resolve rather than cached, is gated on `import.meta.env.DEV`, and never reaches
 * production — `resolveUiLanguage` is the single choke point every locale decision flows through.
 */
function devPreviewLanguage(): UiLanguage | null {
    if (!import.meta.env.DEV || typeof window === 'undefined') return null;
    const requested = new URLSearchParams(window.location.search).get('uiLang');
    const base = baseLanguage(requested ?? '');
    return (SUPPORTED_UI_LANGUAGES as readonly string[]).includes(base) ? (base as UiLanguage) : null;
}

/** The launched UI language for a preference/browser tag; unlaunched languages degrade to `en` (D7). */
export function resolveUiLanguage(tag?: string | null): UiLanguage {
    const preview = devPreviewLanguage();
    if (preview) return preview;
    const base = baseLanguage(tag ?? '');
    return (LAUNCHED_UI_LANGUAGES as readonly string[]).includes(base) ? (base as UiLanguage) : 'en';
}

/** Whether a UI language is written right-to-left. Drives `<html dir>`; see docs/I18N.md D5. */
export function isRtl(language: string): boolean {
    return RTL_UI_LANGUAGES.includes(baseLanguage(language));
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
    if (cached && isUsableLocale(cached)) return cached;
    const candidates = navigator.languages ?? [navigator.language];
    for (const c of candidates) {
        // POSIX-flavoured environments report tags like `en-US@posix`, which `Intl` rejects
        // outright; skip them here so a malformed tag is never selected, cached, or set as
        // `<html lang>` (`setActiveLocale` guards the `Intl` call sites independently).
        if (c && (SUPPORTED_UI_LANGUAGES as readonly string[]).includes(baseLanguage(c)) && isUsableLocale(c)) {
            return c;
        }
    }
    return FALLBACK_LOCALE;
}

/**
 * Points `<html lang/dir>` at the language actually being *rendered*, which is not always the
 * user's preference: an unlaunched preference degrades to an English catalog (D7), and announcing
 * `lang="he"` over English text would mislead screen readers and hyphenation. The region subtag is
 * kept only when it belongs to the rendered language (`en-GB` stays `en-GB`).
 */
function applyDocumentLanguage(ui: UiLanguage, fullTag: string): void {
    const el = document.documentElement;
    el.lang = baseLanguage(fullTag) === ui ? fullTag : ui;
    el.dir = isRtl(ui) ? 'rtl' : 'ltr';
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
    react: {
        useSuspense: false,
        // Non-English catalogs arrive as lazy chunks via `addResourceBundle` after init, which is a
        // store event, not a language change. Without this the new strings sit in the store and the
        // tree keeps showing the English fallback until something else happens to re-render it.
        bindI18nStore: 'added',
    },
});

// Apply the boot locale to Intl + <html> immediately so first paint matches (no flash).
setActiveLocale(bootTag);
applyDocumentLanguage(resolveUiLanguage(bootTag), bootTag);

/**
 * Resolves once the boot language's catalog is loaded. `main.tsx` awaits it before mounting.
 *
 * Two reasons this cannot wait for `applyLocale`: that only runs once `/me` returns a user, so the
 * entire unauthenticated surface (login, policy and invite pages) would never be translated at all;
 * and `<html lang/dir>` is set synchronously above, so an RTL language would paint English text
 * inside mirrored chrome before swapping. For `en` the promise is already settled and nothing is
 * delayed. A failed chunk load resolves too — the English fallback is a fine outcome, a blank page
 * is not.
 */
export const bootCatalogReady: Promise<void> = loadCatalog(resolveUiLanguage(bootTag)).catch(() => {});

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

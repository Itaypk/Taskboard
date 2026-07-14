import { describe, expect, it } from 'vitest';
import en from '../locales/en/translation.json';
import { LAUNCHED_UI_LANGUAGES } from './index';

/**
 * Guards catalog integrity (docs/I18N.md, guard rails). `en` is the source of truth: every value
 * must be a non-empty string. Each *launched* non-English catalog must have exactly the same key
 * set — a missing key would leak English into an otherwise-translated screen, and an extra key is
 * dead weight (or a typo). Phase 1 launches only `en`, so this mostly locks the `en` shape in place;
 * add a language's catalog to `otherCatalogs` when it launches and the parity check covers it.
 */

type Catalog = Record<string, unknown>;

function flatten(obj: Catalog, prefix = ''): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
        const path = prefix ? `${prefix}.${key}` : key;
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
            Object.assign(out, flatten(value as Catalog, path));
        } else {
            out[path] = value;
        }
    }
    return out;
}

const enFlat = flatten(en as Catalog);

// Catalogs for launched non-English languages, keyed by code. Populate as each ships.
const otherCatalogs: Record<string, Catalog> = {
    // he: heTranslation,
};

describe('en translation catalog', () => {
    it('has keys', () => {
        expect(Object.keys(enFlat).length).toBeGreaterThan(0);
    });

    it('has only non-empty string values', () => {
        for (const [key, value] of Object.entries(enFlat)) {
            expect(typeof value, `value for "${key}"`).toBe('string');
            expect((value as string).trim().length, `value for "${key}"`).toBeGreaterThan(0);
        }
    });
});

describe('launched-language key parity with en', () => {
    const launchedNonEn = (LAUNCHED_UI_LANGUAGES as readonly string[]).filter(l => l !== 'en');

    it.each(launchedNonEn)('%s catalog matches en exactly', (lang) => {
        const catalog = otherCatalogs[lang];
        expect(catalog, `no catalog registered for launched language "${lang}"`).toBeDefined();
        const keys = Object.keys(flatten(catalog)).sort();
        expect(keys).toEqual(Object.keys(enFlat).sort());
    });

    // Keeps the suite green (and meaningful) while `en` is the only launched language.
    it('registers a catalog for every launched non-English language', () => {
        for (const lang of launchedNonEn) {
            expect(otherCatalogs[lang], `missing catalog for "${lang}"`).toBeDefined();
        }
    });
});

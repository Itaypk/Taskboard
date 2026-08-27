import { describe, expect, it } from 'vitest';
import en from '../locales/en/translation.json';
import he from '../locales/he/translation.json';
import ru from '../locales/ru/translation.json';
import { LAUNCHED_UI_LANGUAGES } from './index';

/**
 * Guards catalog integrity (docs/I18N.md, guard rails). `en` is the source of truth: every value
 * must be a non-empty string. Each *launched* non-English catalog must cover exactly the same
 * keys — a missing key would leak English into an otherwise-translated screen, and an extra key is
 * dead weight (or a typo).
 *
 * Parity is checked on *base* keys, with i18next's plural suffix stripped, because the set of
 * suffixes is a property of the language, not of the catalog: `en` needs `_one`/`_other`, `he` also
 * needs `_two` (יומיים), `ru` needs `_few`/`_many`, `ar` needs all six. A plural key must therefore
 * carry exactly the CLDR categories `Intl.PluralRules` reports for that language — too few and
 * i18next silently falls back to English for the uncovered counts, which is the exact bug this
 * catches.
 *
 * Interpolation is checked too: a translation that drops `{{name}}` or a `<strong>` tag renders a
 * blank where a value should be, and neither TypeScript nor i18next would complain.
 */

type Catalog = Record<string, unknown>;

const PLURAL_SUFFIXES = ['zero', 'one', 'two', 'few', 'many', 'other'] as const;

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

/** Splits `utils.daysAgo_two` into `['utils.daysAgo', 'two']`; a non-plural key yields a null category. */
function splitPlural(key: string): [base: string, category: string | null] {
    for (const suffix of PLURAL_SUFFIXES) {
        if (key.endsWith(`_${suffix}`)) return [key.slice(0, -(suffix.length + 1)), suffix];
    }
    return [key, null];
}

/** base key → the plural categories it declares, or `null` for a non-plural key. */
function pluralShape(flat: Record<string, unknown>): Map<string, Set<string> | null> {
    const shape = new Map<string, Set<string> | null>();
    for (const key of Object.keys(flat)) {
        const [base, category] = splitPlural(key);
        if (category === null) {
            shape.set(base, null);
        } else {
            const existing = shape.get(base) ?? new Set<string>();
            existing?.add(category);
            shape.set(base, existing);
        }
    }
    return shape;
}

const interpolations = (value: string) => [...value.matchAll(/\{\{(\w+)\}\}/g)].map(m => m[1]);
const markupTags = (value: string) => [...value.matchAll(/<\/?(\w+)>/g)].map(m => m[1]);

const enFlat = flatten(en as Catalog);
const enShape = pluralShape(enFlat);

/** Catalogs for launched non-English languages, keyed by code. Populate as each ships. */
const otherCatalogs: Record<string, Catalog> = {
    he: he as Catalog,
    ru: ru as Catalog,
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

    it('declares the plural categories en actually needs', () => {
        const expected = new Intl.PluralRules('en').resolvedOptions().pluralCategories;
        for (const [base, categories] of enShape) {
            if (categories === null) continue;
            expect([...categories].sort(), `plural key "${base}"`).toEqual([...expected].sort());
        }
    });
});

describe('launched-language key parity with en', () => {
    const launchedNonEn = (LAUNCHED_UI_LANGUAGES as readonly string[]).filter(l => l !== 'en');

    it('registers a catalog for every launched non-English language', () => {
        for (const lang of launchedNonEn) {
            expect(otherCatalogs[lang], `missing catalog for "${lang}"`).toBeDefined();
        }
    });

    describe.each(launchedNonEn)('%s', (lang) => {
        const flat = flatten(otherCatalogs[lang] ?? {});
        const shape = pluralShape(flat);

        it('covers exactly the same base keys as en', () => {
            expect([...shape.keys()].sort()).toEqual([...enShape.keys()].sort());
        });

        it('has only non-empty string values', () => {
            for (const [key, value] of Object.entries(flat)) {
                expect(typeof value, `value for "${key}"`).toBe('string');
                expect((value as string).trim().length, `value for "${key}"`).toBeGreaterThan(0);
            }
        });

        it('pluralises exactly the keys en pluralises, with this language\'s CLDR categories', () => {
            const expected = [...new Intl.PluralRules(lang).resolvedOptions().pluralCategories].sort();
            for (const [base, enCategories] of enShape) {
                const categories = shape.get(base);
                if (enCategories === null) {
                    expect(categories, `"${base}" is not plural in en`).toBeNull();
                } else {
                    expect(categories, `"${base}" is plural in en`).not.toBeNull();
                    expect([...(categories ?? [])].sort(), `plural key "${base}"`).toEqual(expected);
                }
            }
        });

        it('keeps every interpolation and markup tag en uses', () => {
            for (const [base] of enShape) {
                const collect = (source: Record<string, unknown>) => {
                    const values = Object.entries(source)
                        .filter(([key]) => splitPlural(key)[0] === base)
                        .map(([, value]) => String(value));
                    return {
                        // `count` is supplied by i18next on every plural form, so a form that reads
                        // naturally without it (יומיים) is correct, not a dropped placeholder.
                        vars: new Set(values.flatMap(interpolations).filter(v => v !== 'count')),
                        tags: new Set(values.flatMap(markupTags)),
                    };
                };
                const source = collect(enFlat);
                const target = collect(flat);
                expect([...target.vars].sort(), `interpolations for "${base}"`).toEqual([...source.vars].sort());
                expect([...target.tags].sort(), `markup tags for "${base}"`).toEqual([...source.tags].sort());
            }
        });
    });
});

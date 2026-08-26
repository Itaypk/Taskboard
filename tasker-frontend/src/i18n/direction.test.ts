import { describe, expect, it } from 'vitest';
import { isRtl, LAUNCHED_UI_LANGUAGES, SUPPORTED_UI_LANGUAGES } from './index';

/**
 * Guards the direction half of the RTL pass (docs/I18N.md, D5). The CSS mirrors itself via logical
 * properties, but every one of those rules is downstream of `<html dir>` being correct, so the
 * language→direction mapping is worth pinning independently of any component.
 */
describe('isRtl', () => {
    it.each(['he', 'ar'])('treats %s as right-to-left', (lang) => {
        expect(isRtl(lang)).toBe(true);
    });

    it.each(['en', 'ru'])('treats %s as left-to-right', (lang) => {
        expect(isRtl(lang)).toBe(false);
    });

    it('resolves the region subtag against the base language', () => {
        expect(isRtl('en-GB')).toBe(false);
        expect(isRtl('ar-EG')).toBe(true);
    });

    it('is case-insensitive', () => {
        expect(isRtl('HE')).toBe(true);
    });

    it('treats an unknown or empty tag as left-to-right', () => {
        expect(isRtl('')).toBe(false);
        expect(isRtl('xx')).toBe(false);
    });

    it('classifies every supported language', () => {
        for (const lang of SUPPORTED_UI_LANGUAGES) {
            expect(typeof isRtl(lang), `direction for "${lang}"`).toBe('boolean');
        }
    });
});

describe('launched languages', () => {
    it('are all in the supported list', () => {
        for (const lang of LAUNCHED_UI_LANGUAGES) {
            expect(SUPPORTED_UI_LANGUAGES as readonly string[]).toContain(lang);
        }
    });
});

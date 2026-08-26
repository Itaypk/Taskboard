import { beforeEach, describe, expect, it } from 'vitest';
import { formatDate, formatTime, getActiveLocale, isUsableLocale, setActiveLocale } from './format';

/**
 * The locale tag reaching `Intl` is only partly under our control — it can come from
 * `navigator.languages`, a `localStorage` cache, or a stored user preference — and a structurally
 * invalid tag makes `Intl` throw rather than degrade. Since dates are formatted during render,
 * that would blank the screen, so the fallback is load-bearing rather than cosmetic.
 */

const SAMPLE = new Date('2026-03-04T09:05:00Z');

beforeEach(() => setActiveLocale('en-US'));

describe('isUsableLocale', () => {
    it.each(['en-US', 'en-GB', 'he', 'ru', 'ar-EG', 'zz-ZZ'])('accepts the well-formed tag %s', (tag) => {
        expect(isUsableLocale(tag)).toBe(true);
    });

    it.each(['en-US@posix', 'C.UTF-8', 'en_US', ''])('rejects the malformed tag "%s"', (tag) => {
        expect(isUsableLocale(tag)).toBe(false);
    });
});

describe('setActiveLocale', () => {
    it('keeps a usable tag as given, region subtag included', () => {
        setActiveLocale('en-GB');
        expect(getActiveLocale()).toBe('en-GB');
    });

    it.each([null, undefined, '', '   '])('falls back to en-US for %o', (tag) => {
        setActiveLocale('he');
        setActiveLocale(tag);
        expect(getActiveLocale()).toBe('en-US');
    });

    it('falls back to en-US for a tag Intl would reject', () => {
        setActiveLocale('en-US@posix');
        expect(getActiveLocale()).toBe('en-US');
    });

    it('trims surrounding whitespace', () => {
        setActiveLocale('  he  ');
        expect(getActiveLocale()).toBe('he');
    });
});

describe('formatting never throws on a hostile locale', () => {
    it.each(['en-US@posix', 'C.UTF-8', 'en_US'])('formats dates and times after %s is set', (tag) => {
        setActiveLocale(tag);
        expect(() => formatDate(SAMPLE)).not.toThrow();
        expect(() => formatTime(SAMPLE)).not.toThrow();
        expect(formatDate(SAMPLE)).toBeTruthy();
    });

    it('accepts an ISO string as well as a Date', () => {
        expect(formatDate(SAMPLE.toISOString())).toBe(formatDate(SAMPLE));
    });

    it('honours the active locale', () => {
        setActiveLocale('en-US');
        const us = formatDate(SAMPLE, { day: 'numeric', month: 'numeric', year: 'numeric' });
        setActiveLocale('en-GB');
        const gb = formatDate(SAMPLE, { day: 'numeric', month: 'numeric', year: 'numeric' });
        expect(us).not.toBe(gb);
    });
});

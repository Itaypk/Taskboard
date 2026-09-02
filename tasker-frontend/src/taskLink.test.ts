import { describe, expect, it, afterAll } from 'vitest';
import { linkLabel, resolveTaskLink } from './taskLink';
import { applyLocale } from './i18n';

describe('resolveTaskLink', () => {
    it('classifies the three shapes a task url can take', () => {
        expect(resolveTaskLink('https://example.com/x')).toEqual({ kind: 'external', href: 'https://example.com/x' });
        expect(resolveTaskLink('/settings/general')).toEqual({ kind: 'internal', to: '/settings/general' });
        expect(resolveTaskLink('app:clear-tutorial')).toEqual({ kind: 'action', action: 'clear-tutorial' });
    });

    it('rejects anything outside the allowlist rather than rendering it', () => {
        // The allowlist is a safety boundary, not a convenience: an unsafe scheme must never reach
        // window.open, and an unknown app: token must render nothing rather than crash.
        expect(resolveTaskLink('javascript:alert(1)')).toBeNull();
        expect(resolveTaskLink('data:text/html,<script>')).toBeNull();
        expect(resolveTaskLink('app:not-a-real-action')).toBeNull();
        expect(resolveTaskLink('   ')).toBeNull();
        expect(resolveTaskLink(null)).toBeNull();
    });
});

describe('linkLabel', () => {
    afterAll(async () => { await applyLocale('en-US'); });

    it('translates the friendly names instead of hardcoding English', async () => {
        const internal = resolveTaskLink('/settings/general');
        const action = resolveTaskLink('app:clear-tutorial');

        expect(linkLabel(internal)).toBe('Open settings');
        expect(linkLabel(action)).toBe('Clear tutorial tasks');

        await applyLocale('he');

        // The point of the test: these must follow the active language. The tutorial cards
        // themselves are localized, so an English affordance under a Hebrew card is a bug.
        expect(linkLabel(internal)).not.toBe('Open settings');
        expect(linkLabel(action)).not.toBe('Clear tutorial tasks');
        expect(linkLabel(action)).toMatch(/[֐-׿]/);
    });

    it('leaves an external URL alone, prettified and truncated', async () => {
        await applyLocale('he');
        const link = resolveTaskLink('https://www.example.com/some/path/');

        // A URL is not translatable; it is stripped of scheme/www/trailing slash only.
        expect(linkLabel(link)).toBe('example.com/some/path');
        expect(linkLabel(resolveTaskLink(`https://example.com/${'a'.repeat(60)}`))).toHaveLength(28);
    });

    it('has no label for an unresolvable url', () => {
        expect(linkLabel(null)).toBe('');
    });
});

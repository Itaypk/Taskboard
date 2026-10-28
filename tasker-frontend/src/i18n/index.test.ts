import { afterEach, describe, expect, it } from 'vitest';
import i18n, { applyLocale } from './index';

describe('British English', () => {
    afterEach(async () => {
        await applyLocale('en-US');
    });

    it('renders the en-GB spelling overrides and falls back to en for everything else', async () => {
        await applyLocale('en-GB');
        expect(i18n.t('tagEditModal.color')).toBe('Colour');
        expect(i18n.t('login.nav.logIn')).toBe('Log in');
    });

    it('leaves US English on the base catalog', async () => {
        await applyLocale('en-US');
        expect(i18n.language).toBe('en');
        expect(i18n.t('tagEditModal.color')).toBe('Color');
    });
});

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import LanguageSelector from './LanguageSelector';
import { applyLocale } from '../i18n';
import { getActiveLocale } from '../i18n/format';

describe('LanguageSelector', () => {
    beforeEach(async () => {
        window.localStorage.clear();
        await applyLocale('en-US');
    });

    // vitest runs without `globals`, so RTL's automatic afterEach cleanup never registers and
    // renders would otherwise stack across tests in this file.
    afterEach(cleanup);

    it('offers each language under its own name, so a reader can find their own', () => {
        render(<LanguageSelector />);

        const options = screen.getAllByRole('option').map(o => o.textContent);
        expect(options).toEqual(expect.arrayContaining(['English', 'עברית', 'Русский', 'العربية']));
    });

    it('switches the active locale and flips document direction for an RTL choice', async () => {
        render(<LanguageSelector />);

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'he' } });

        // applyLocale is async (it may lazy-load the catalog first).
        await waitFor(() => expect(getActiveLocale()).toBe('he'));
        expect(document.documentElement.lang).toBe('he');
        expect(document.documentElement.dir).toBe('rtl');
    });

    it('reflects the newly selected language as the combobox value', async () => {
        render(<LanguageSelector />);

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'ru' } });

        await waitFor(() => expect(getActiveLocale()).toBe('ru'));
        expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('ru');
    });

    it('persists the choice so the next boot does not fall back to the browser', async () => {
        render(<LanguageSelector />);

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'ru' } });

        // detectPreferredTag reads this cache ahead of navigator.languages.
        await waitFor(() => expect(window.localStorage.getItem('backlog.locale')).toBe('ru'));
        expect(document.documentElement.dir).toBe('ltr');
    });
});

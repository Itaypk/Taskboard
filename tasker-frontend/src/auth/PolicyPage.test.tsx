import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import i18n from '../i18n';
import { TermsPage } from './PolicyPage';

function renderTerms() {
  render(
    <MemoryRouter>
      <TermsPage />
    </MemoryRouter>,
  );
}

describe('PolicyPage', () => {
  afterEach(async () => {
    await act(() => i18n.changeLanguage('en'));
  });

  it('shows no English-only notice in English', () => {
    renderTerms();
    expect(screen.queryByText('This page is available in English only.')).not.toBeInTheDocument();
  });

  it('says the page is English-only in another UI language', async () => {
    const ru = await import('../locales/ru/translation.json');
    i18n.addResourceBundle('ru', 'translation', ru.default, true, true);
    await act(() => i18n.changeLanguage('ru'));
    renderTerms();
    expect(screen.getByText('Эта страница доступна только на английском языке.')).toBeInTheDocument();
  });
});

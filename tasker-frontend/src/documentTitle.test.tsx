import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { act } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import i18n from './i18n';
import { useDocumentTitle } from './documentTitle';

function TitleOwner() {
  useDocumentTitle();
  return null;
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <TitleOwner />
    </MemoryRouter>,
  );
}

describe('useDocumentTitle', () => {
  afterEach(async () => {
    await act(() => i18n.changeLanguage('en'));
  });

  it('uses the home title for routes without one of their own', () => {
    renderAt('/settings');
    expect(document.title).toBe('Backlog.fyi - your personal tasks planner');
  });

  it('titles content pages after the page, trailing slash or not', () => {
    renderAt('/privacy/');
    expect(document.title).toBe('Privacy Policy — Backlog.fyi');
  });

  it('follows a language switch', async () => {
    const he = await import('./locales/he/translation.json');
    i18n.addResourceBundle('he', 'translation', he.default, true, true);
    renderAt('/terms');
    await act(() => i18n.changeLanguage('he'));
    expect(document.title).toBe('תנאי שימוש — Backlog.fyi');
  });
});

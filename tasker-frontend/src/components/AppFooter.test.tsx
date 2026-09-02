import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, afterEach } from 'vitest';
import AppFooter from './AppFooter';

const EXPECTED: [string, string][] = [
  ['About', '/about'],
  ['FAQ', '/faq'],
  ['Terms', '/terms'],
  ['Privacy', '/privacy'],
];

/** Stubs the compact breakpoint; jsdom has no layout, so the component can't detect it itself. */
function stubViewport(compact: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: compact,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

afterEach(() => vi.unstubAllGlobals());

describe('AppFooter', () => {
  it('links to every static content page on wide screens', () => {
    stubViewport(false);
    render(<MemoryRouter><AppFooter /></MemoryRouter>);

    for (const [name, href] of EXPECTED) {
      expect(screen.getByRole('link', { name })).toHaveAttribute('href', href);
    }
  });

  it('folds the links into a menu on phones', () => {
    stubViewport(true);
    const { container } = render(<MemoryRouter><AppFooter /></MemoryRouter>);

    expect(container.querySelectorAll('a')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'More links' }));

    for (const [name, href] of EXPECTED) {
      expect(screen.getByRole('menuitem', { name })).toBeInTheDocument();
      expect(screen.getByRole('menuitem', { name })).toHaveAttribute('href', href);
    }
  });

  it('offers the language switcher only to anonymous visitors', () => {
    stubViewport(false);
    const { unmount } = render(<MemoryRouter><AppFooter showLanguage /></MemoryRouter>);
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeInTheDocument();
    unmount();

    // Signed in, language lives in Settings instead — where it is stored on the account.
    render(<MemoryRouter><AppFooter /></MemoryRouter>);
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });
});

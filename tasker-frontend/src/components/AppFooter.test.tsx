import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import AppFooter from './AppFooter';

describe('AppFooter', () => {
  it('links to every static content page', () => {
    render(
      <MemoryRouter>
        <AppFooter />
      </MemoryRouter>,
    );

    for (const [name, href] of [['About', '/about'], ['FAQ', '/faq'], ['Terms', '/terms'], ['Privacy', '/privacy']]) {
      expect(screen.getByRole('link', { name })).toHaveAttribute('href', href);
    }
  });
});

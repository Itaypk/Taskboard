import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import MarkdownRenderer from './MarkdownRenderer';

function renderMarkdown(content: string) {
  return render(
    <MemoryRouter initialEntries={['/about']}>
      <Routes>
        <Route path="/about" element={<MarkdownRenderer content={content} />} />
        <Route path="/faq" element={<p>faq page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('MarkdownRenderer links', () => {
  it('opens external links in a new tab', () => {
    renderMarkdown('[example](https://example.com)');

    const link = screen.getByRole('link', { name: 'example' });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('treats protocol-relative links as external', () => {
    renderMarkdown('[protocol relative](//example.com)');

    expect(screen.getByRole('link', { name: 'protocol relative' })).toHaveAttribute('target', '_blank');
  });

  it('keeps in-app links in the same tab and routes them', () => {
    renderMarkdown('[the faq](/faq)');

    const link = screen.getByRole('link', { name: 'the faq' });
    expect(link).not.toHaveAttribute('target');

    fireEvent.click(link, { button: 0 });
    expect(screen.getByText('faq page')).toBeInTheDocument();
  });
});

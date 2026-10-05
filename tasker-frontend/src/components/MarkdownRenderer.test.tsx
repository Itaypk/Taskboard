import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
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

describe('MarkdownRenderer checklists', () => {
  const checklist = '- [ ] milk\n- [x] eggs';

  function renderChecklist(onToggleCheckbox?: (index: number) => void) {
    return render(
      <MemoryRouter>
        <MarkdownRenderer content={checklist} onToggleCheckbox={onToggleCheckbox} />
      </MemoryRouter>,
    );
  }

  it('renders read-only checkboxes without a toggle handler', () => {
    renderChecklist();

    for (const box of screen.getAllByRole('checkbox')) expect(box).toBeDisabled();
  });

  it('reports the clicked checkbox index and leaves the flip to the new content', () => {
    const onToggle = vi.fn();
    renderChecklist(onToggle);

    const boxes = screen.getAllByRole('checkbox');
    expect(boxes[1]).toBeEnabled();
    fireEvent.click(boxes[1]);

    expect(onToggle).toHaveBeenCalledWith(1);
    expect(boxes[1]).toBeChecked();
  });

  it('keeps checkbox clicks and keys from reaching the surrounding card', () => {
    const onCardClick = vi.fn();
    const onCardKey = vi.fn();
    render(
      <MemoryRouter>
        <div onClick={onCardClick} onKeyDown={onCardKey}>
          <MarkdownRenderer content={checklist} onToggleCheckbox={() => {}} />
        </div>
      </MemoryRouter>,
    );

    const box = screen.getAllByRole('checkbox')[0];
    fireEvent.click(box);
    fireEvent.keyDown(box, { key: ' ' });

    expect(onCardClick).not.toHaveBeenCalled();
    expect(onCardKey).not.toHaveBeenCalled();
  });
});

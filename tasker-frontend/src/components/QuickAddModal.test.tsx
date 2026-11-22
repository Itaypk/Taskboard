import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QuickAddModal } from './QuickAddModal';

vi.mock('../api', () => ({
  ApiError: class extends Error {},
  startQuickAdd: vi.fn(),
  replyQuickAdd: vi.fn(),
  abandonQuickAdd: vi.fn(() => Promise.resolve()),
}));

function renderModal(open: boolean, onClose = vi.fn()) {
  render(
    <MemoryRouter>
      <QuickAddModal open={open} boardId="board-1" onClose={onClose} onBoardChanged={vi.fn()} />
    </MemoryRouter>,
  );
  return onClose;
}

describe('QuickAddModal', () => {
  afterEach(() => cleanup());

  it('shows the capture with the import prompt while open', () => {
    renderModal(true);

    expect(screen.getByRole('dialog', { name: 'Add your existing tasks' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Paste the list you keep/ })).toBeInTheDocument();
  });

  it('keeps the capture unmounted while closed, so reopening starts fresh', () => {
    renderModal(false);

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('closes on Escape', () => {
    const onClose = renderModal(true);

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QuickAddCapture } from './QuickAddCapture';

const startQuickAdd = vi.fn();
const replyQuickAdd = vi.fn();
const abandonQuickAdd = vi.fn();
vi.mock('../api', () => ({
  ApiError: class extends Error {
    status: number;
    constructor(status: number) { super('api error'); this.status = status; }
  },
  startQuickAdd: (...args: unknown[]) => startQuickAdd(...args),
  replyQuickAdd: (...args: unknown[]) => replyQuickAdd(...args),
  abandonQuickAdd: (...args: unknown[]) => abandonQuickAdd(...args),
}));

const draftCard = {
  active: true,
  boardChanged: false,
  messages: [{
    type: 'choice' as const,
    text: '**Dentist**',
    completions: [],
    options: [{ id: 'quickadd_save', label: 'Save' }, { id: 'quickadd_cancel', label: 'Cancel' }],
  }],
};
const saved = {
  active: false,
  boardChanged: true,
  messages: [{ type: 'text' as const, text: 'Added: Dentist', completions: [], options: [] }],
};

// Router context because assistant bubbles render through MarkdownRenderer, which links internally.
function renderCapture(props: { onBoardChanged?: () => void; onFinished?: () => void } = {}) {
  return render(
    <MemoryRouter>
      <QuickAddCapture
        boardId="board-1"
        prompt="What's on your plate?"
        onBoardChanged={props.onBoardChanged ?? (() => {})}
        onFinished={props.onFinished}
      />
    </MemoryRouter>,
  );
}

async function draft(text = 'dentist thursday') {
  fireEvent.change(screen.getByLabelText("What's on your plate?"), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Turn into tasks' }));
  return screen.findByRole('button', { name: 'Save' });
}

describe('QuickAddCapture', () => {
  // Leaving mid-draft abandons it, so every test that ends on a draft makes this call on cleanup.
  beforeEach(() => { abandonQuickAdd.mockResolvedValue(undefined); });
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('drafts from the typed text and shows the draft for review', async () => {
    startQuickAdd.mockResolvedValue(draftCard);
    renderCapture();

    await draft('  dentist thursday ');

    expect(startQuickAdd).toHaveBeenCalledWith('board-1', 'dentist thursday');
    expect(screen.getByText('Dentist')).toBeInTheDocument();
  });

  it('saves by option id, tells the host the board changed, and finishes on Done', async () => {
    const onBoardChanged = vi.fn();
    const onFinished = vi.fn();
    startQuickAdd.mockResolvedValue(draftCard);
    replyQuickAdd.mockResolvedValue(saved);
    renderCapture({ onBoardChanged, onFinished });

    fireEvent.click(await draft());

    await waitFor(() => expect(onBoardChanged).toHaveBeenCalled());
    // The label belongs in the transcript, not in the request: the server binds the choice by id.
    expect(replyQuickAdd).toHaveBeenCalledWith('board-1', { optionId: 'quickadd_save' });
    expect(onFinished).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(onFinished).toHaveBeenCalled();
    // Back to the text box, ready for another capture.
    expect(screen.getByLabelText("What's on your plate?")).toHaveValue('');
  });

  it('sends typed text as an adjustment to the draft', async () => {
    startQuickAdd.mockResolvedValue(draftCard);
    replyQuickAdd.mockResolvedValue(draftCard);
    renderCapture();
    await draft();

    fireEvent.change(screen.getByLabelText('Say what to change…'), { target: { value: 'make it friday' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(replyQuickAdd).toHaveBeenCalledWith('board-1', { text: 'make it friday' }));
  });

  it('keeps the typed text when the draft request fails', async () => {
    startQuickAdd.mockRejectedValue(new Error('boom'));
    renderCapture();

    fireEvent.change(screen.getByLabelText("What's on your plate?"), { target: { value: 'dentist thursday' } });
    fireEvent.click(screen.getByRole('button', { name: 'Turn into tasks' }));

    await waitFor(() => expect(screen.getByLabelText("What's on your plate?")).toHaveValue('dentist thursday'));
  });

  it('drops a draft left unconfirmed when it goes away', async () => {
    startQuickAdd.mockResolvedValue(draftCard);
    const { unmount } = renderCapture();
    await draft();

    unmount();

    expect(abandonQuickAdd).toHaveBeenCalledWith('board-1');
  });
});

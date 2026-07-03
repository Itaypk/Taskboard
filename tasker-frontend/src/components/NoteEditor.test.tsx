import { StrictMode } from 'react';
import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NoteEditor } from './NoteEditor';

// RTL auto-cleanup only registers when Vitest globals are on; this project runs without them,
// so unmount between tests explicitly to avoid leaked DOM producing duplicate-match queries.
afterEach(cleanup);

describe('NoteEditor', () => {
  it('renders the provided Markdown as rich content', async () => {
    const { container } = render(<NoteEditor value="Bring **prior** x-rays" onChange={() => {}} />);
    await waitFor(() => {
      const strong = container.querySelector('strong');
      expect(strong).not.toBeNull();
      expect(strong?.textContent).toBe('prior');
    });
  });

  it('exposes the raw Markdown behind the "Edit as Markdown" toggle and edits sync out', async () => {
    const onChange = vi.fn();
    render(<NoteEditor value={'- one\n- two'} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Edit as Markdown' }));

    const raw = await screen.findByRole('textbox');
    expect(raw).toHaveValue('- one\n- two');

    fireEvent.change(raw, { target: { value: '- one\n- two\n- three' } });
    expect(onChange).toHaveBeenCalledWith('- one\n- two\n- three');
  });

  it('loads an existing note under StrictMode without crashing on a torn-down editor', async () => {
    // StrictMode double-mounts the editor; the initial content load must survive the
    // destroy/recreate cycle (regression: "commandManager is null" on non-empty notes).
    const { container } = render(
      <StrictMode>
        <NoteEditor value={'## Dentist\n\nBring **prior** x-rays.'} onChange={() => {}} />
      </StrictMode>,
    );
    await waitFor(() => {
      expect(container.querySelector('h2')?.textContent).toBe('Dentist');
      expect(container.querySelector('strong')?.textContent).toBe('prior');
    });
  });

  it('disables editing when disabled', async () => {
    const { container } = render(<NoteEditor value="hello" onChange={() => {}} disabled />);
    await waitFor(() => {
      const editable = container.querySelector('[contenteditable]');
      expect(editable).toHaveAttribute('contenteditable', 'false');
    });
  });
});

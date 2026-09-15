import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ToastStack } from './ToastStack';
import { notifyToast } from '../toast';
import { notifyError } from '../api';

/** Toasts arrive through window events, so every case dispatches rather than passing props. */
function emit(fn: () => void) {
  act(fn);
}

describe('ToastStack', () => {
  it('renders nothing until a toast arrives', () => {
    const { container } = render(<ToastStack />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows an action and runs it on click, dismissing the toast', () => {
    const undo = vi.fn();
    render(<ToastStack />);

    emit(() => notifyToast({ kind: 'success', message: 'Marked as done', action: { label: 'Undo', onClick: undo } }));
    expect(screen.getByText('Marked as done')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(undo).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Marked as done')).not.toBeInTheDocument();
  });

  it('replaces a keyed toast instead of stacking it', () => {
    render(<ToastStack />);

    emit(() => notifyToast({ key: 'task-done', message: 'first' }));
    emit(() => notifyToast({ key: 'task-done', message: 'second' }));

    expect(screen.queryByText('first')).not.toBeInTheDocument();
    expect(screen.getByText('second')).toBeInTheDocument();
  });

  it('collapses unkeyed duplicates of the same message', () => {
    render(<ToastStack />);

    emit(() => notifyError('Something broke'));
    emit(() => notifyError('Something broke'));

    expect(screen.getAllByText('Something broke')).toHaveLength(1);
    // API errors are assertive: a failed save must interrupt, a confirmation must not.
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('auto-dismisses after the default delay but keeps a persistent toast up', () => {
    vi.useFakeTimers();
    try {
      render(<ToastStack />);
      emit(() => notifyToast({ message: 'transient' }));
      emit(() => notifyToast({ key: 'app-update', message: 'A new version is available.', durationMs: 0 }));

      act(() => { vi.advanceTimersByTime(10_000); });

      expect(screen.queryByText('transient')).not.toBeInTheDocument();
      expect(screen.getByText('A new version is available.')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('dismisses on the close button', () => {
    render(<ToastStack />);
    emit(() => notifyToast({ message: 'bye' }));

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText('bye')).not.toBeInTheDocument();
  });
});

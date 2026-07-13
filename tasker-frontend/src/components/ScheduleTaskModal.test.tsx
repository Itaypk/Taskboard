import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScheduleTaskModal } from './ScheduleTaskModal';

function timeInputs(container: HTMLElement): { start: HTMLInputElement; end: HTMLInputElement } {
  const inputs = container.querySelectorAll<HTMLInputElement>('input[type="time"]');
  return { start: inputs[0], end: inputs[1] };
}

const baseProps = {
  open: true,
  taskTitle: 'Write report',
  weekStart: '2026-05-11',
  weekEnd: '2026-05-17',
  onConfirm: vi.fn(),
  onClose: vi.fn(),
};

describe('ScheduleTaskModal', () => {
  afterEach(cleanup);

  it('auto-fills the end time from the start plus the estimated duration', () => {
    const { container } = render(<ScheduleTaskModal {...baseProps} estimatedMinutes={60} />);
    const { start, end } = timeInputs(container);

    fireEvent.change(start, { target: { value: '09:00' } });

    expect(end.value).toBe('10:00');
  });

  it('defaults to a 30-minute block when no estimate is set', () => {
    const { container } = render(<ScheduleTaskModal {...baseProps} />);
    const { start, end } = timeInputs(container);

    fireEvent.change(start, { target: { value: '14:00' } });

    expect(end.value).toBe('14:30');
  });

  it('auto-fills the start time backwards when the end is chosen first', () => {
    const { container } = render(<ScheduleTaskModal {...baseProps} estimatedMinutes={60} />);
    const { start, end } = timeInputs(container);

    fireEvent.change(end, { target: { value: '15:00' } });

    expect(start.value).toBe('14:00');
  });

  it('does not overwrite a field the user has edited by hand', () => {
    const { container } = render(<ScheduleTaskModal {...baseProps} estimatedMinutes={60} />);
    const { start, end } = timeInputs(container);

    // Start → end auto-links to 10:00.
    fireEvent.change(start, { target: { value: '09:00' } });
    expect(end.value).toBe('10:00');

    // User overrides the end by hand; start is now considered touched, end too.
    fireEvent.change(end, { target: { value: '11:00' } });

    // Changing the start again must NOT move the manually-set end.
    fireEvent.change(start, { target: { value: '08:00' } });
    expect(end.value).toBe('11:00');
  });

  it('renders reschedule affordances and prefilled inputs when given an existing slot', () => {
    const initialSlot = { startIso: '2026-05-13T14:00:00+00:00', endIso: '2026-05-13T15:00:00+00:00' };
    const { container, getByText } = render(
      <ScheduleTaskModal {...baseProps} initialSlot={initialSlot} />,
    );

    expect(getByText('Reschedule task')).toBeInTheDocument();
    expect(getByText('Update slot')).toBeInTheDocument();
    // Inputs are prefilled (exact wall-clock depends on the runner's timezone, so just assert non-empty).
    const { start, end } = timeInputs(container);
    expect(start.value).not.toBe('');
    expect(end.value).not.toBe('');
  });
});

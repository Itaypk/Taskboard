import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventsSection } from './EventsSection';
import type { OneOffEvent } from '../types';

const { cancelEvent } = vi.hoisted(() => ({ cancelEvent: vi.fn() }));
vi.mock('../api', () => ({ cancelEvent }));

const futureEvent: OneOffEvent = {
  id: 'future-1',
  title: 'Dentist',
  startsAt: '2999-01-01T09:00:00Z',
  endsAt: '2999-01-01T10:00:00Z',
  location: null,
  notes: null,
};

const pastEvent: OneOffEvent = {
  id: 'past-1',
  title: 'Old meeting',
  startsAt: '2000-01-01T09:00:00Z',
  endsAt: '2000-01-01T10:00:00Z',
  location: null,
  notes: null,
};

describe('EventsSection', () => {
  afterEach(() => {
    cleanup();
    cancelEvent.mockReset();
  });

  it('shows a cancel button only for events that have not started yet', () => {
    render(<EventsSection events={[futureEvent, pastEvent]} onCancelled={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Cancel Dentist' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel Old meeting' })).not.toBeInTheDocument();
  });

  it('cancels the event and notifies the parent on success', async () => {
    cancelEvent.mockResolvedValue(undefined);
    const onCancelled = vi.fn();
    render(<EventsSection events={[futureEvent]} onCancelled={onCancelled} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel Dentist' }));

    expect(cancelEvent).toHaveBeenCalledWith('future-1');
    await waitFor(() => expect(onCancelled).toHaveBeenCalledWith('future-1'));
  });

  it('leaves the event in place when the cancel request fails', async () => {
    cancelEvent.mockRejectedValue(new Error('boom'));
    const onCancelled = vi.fn();
    render(<EventsSection events={[futureEvent]} onCancelled={onCancelled} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel Dentist' }));

    await waitFor(() => expect(cancelEvent).toHaveBeenCalled());
    expect(onCancelled).not.toHaveBeenCalled();
  });
});

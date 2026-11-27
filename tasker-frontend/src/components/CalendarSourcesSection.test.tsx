import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { CalendarSourcesSection } from './CalendarSourcesSection';

const { fetchCalendarSources, addCalendarSource, updateCalendarSource, deleteCalendarSource, refreshCalendarSource } = vi.hoisted(() => ({
  fetchCalendarSources: vi.fn(),
  addCalendarSource: vi.fn(),
  updateCalendarSource: vi.fn(),
  deleteCalendarSource: vi.fn(),
  refreshCalendarSource: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, fetchCalendarSources, addCalendarSource, updateCalendarSource, deleteCalendarSource, refreshCalendarSource };
});

const work = {
  id: 's1',
  label: 'Work',
  host: 'outlook.office365.com',
  detail: 'TITLES',
  createdAt: '2026-11-20T08:00:00Z',
  lastAttemptAt: new Date().toISOString(),
  lastSuccessAt: new Date().toISOString(),
  lastError: null,
  lastEventCount: 23,
};
const holidays = { ...work, id: 's2', label: 'Holidays', host: 'calendar.google.com', lastError: 'NOT_FOUND', lastEventCount: null };

describe('CalendarSourcesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(cleanup);

  it('lists calendars by name and host, with their read status', async () => {
    fetchCalendarSources.mockResolvedValue([work, holidays]);
    render(<CalendarSourcesSection />);

    expect(await screen.findByRole('button', { name: 'Work' })).toBeInTheDocument();
    expect(screen.getByText('outlook.office365.com')).toBeInTheDocument();
    expect(screen.getByText(/23 events in the next 7 days/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing was found at that address/)).toBeInTheDocument();
  });

  it('adds a calendar and clears the form', async () => {
    fetchCalendarSources.mockResolvedValue([]);
    addCalendarSource.mockResolvedValue(work);
    render(<CalendarSourcesSection />);

    fireEvent.change(await screen.findByLabelText('Secret iCal address'), { target: { value: ' https://example.com/basic.ics ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add calendar' }));

    expect(await screen.findByRole('button', { name: 'Work' })).toBeInTheDocument();
    expect(addCalendarSource).toHaveBeenCalledWith('https://example.com/basic.ics', null, 'TITLES');
    expect(screen.getByLabelText('Secret iCal address')).toHaveValue('');
  });

  it('keeps the address and says why when the calendar cannot be read', async () => {
    fetchCalendarSources.mockResolvedValue([]);
    addCalendarSource.mockRejectedValue(new ApiError({ status: 400, statusText: 'Bad Request', path: '/calendar-sources', userMessage: '', code: 'NOT_A_CALENDAR' }));
    render(<CalendarSourcesSection />);

    fireEvent.change(await screen.findByLabelText('Secret iCal address'), { target: { value: 'https://example.com/page' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add calendar' }));

    expect(await screen.findByText(/That's a web page, not a calendar/)).toBeInTheDocument();
    expect(screen.getByLabelText('Secret iCal address')).toHaveValue('https://example.com/page');
  });

  it('switches what the planner sees', async () => {
    fetchCalendarSources.mockResolvedValue([work]);
    updateCalendarSource.mockResolvedValue({ ...work, detail: 'BUSY' });
    render(<CalendarSourcesSection />);

    fireEvent.change(await screen.findByLabelText('What the planner sees in Work'), { target: { value: 'BUSY' } });

    await waitFor(() => expect(screen.getByLabelText('What the planner sees in Work')).toHaveValue('BUSY'));
    expect(updateCalendarSource).toHaveBeenCalledWith('s1', { detail: 'BUSY' });
  });

  it('removes a calendar after confirming', async () => {
    fetchCalendarSources.mockResolvedValue([work]);
    deleteCalendarSource.mockResolvedValue(undefined);
    render(<CalendarSourcesSection />);

    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Work' })).not.toBeInTheDocument());
    expect(deleteCalendarSource).toHaveBeenCalledWith('s1');
  });

  it('replaces the form with the limit at five calendars', async () => {
    fetchCalendarSources.mockResolvedValue([1, 2, 3, 4, 5].map(i => ({ ...work, id: `s${i}`, label: `Cal ${i}` })));
    render(<CalendarSourcesSection />);

    expect(await screen.findByText('Up to 5 calendars.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Secret iCal address')).not.toBeInTheDocument();
  });
});

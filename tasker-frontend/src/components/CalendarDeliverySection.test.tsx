import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CalendarDeliverySection } from './CalendarDeliverySection';

const { fetchCalendarDelivery, previewCalendarDeliverySwitch, updateCalendarDelivery, rotateCalendarFeed } = vi.hoisted(() => ({
  fetchCalendarDelivery: vi.fn(),
  previewCalendarDeliverySwitch: vi.fn(),
  updateCalendarDelivery: vi.fn(),
  rotateCalendarFeed: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, fetchCalendarDelivery, previewCalendarDeliverySwitch, updateCalendarDelivery, rotateCalendarFeed };
});

const feed = {
  url: 'https://app.example.com/calendar/abc.ics',
  webcalUrl: 'webcal://app.example.com/calendar/abc.ics',
  googleUrl: 'https://calendar.google.com/calendar/r?cid=webcal%3A%2F%2Fapp.example.com%2Fcalendar%2Fabc.ics',
  createdAt: '2026-11-01T10:00:00Z',
  lastFetchedAt: null,
};
const emailState = { delivery: 'EMAIL', emailAvailable: true, feed: null };
const feedState = { delivery: 'FEED', emailAvailable: true, feed };

describe('CalendarDeliverySection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(cleanup);

  it('confirms with the number of invitations to cancel before switching to the feed', async () => {
    fetchCalendarDelivery.mockResolvedValue(emailState);
    previewCalendarDeliverySwitch.mockResolvedValue({ invitationsToCancel: 6, invitationsToSend: 0 });
    updateCalendarDelivery.mockResolvedValue(feedState);
    render(<CalendarDeliverySection onVerifyEmail={() => {}} />);

    fireEvent.click(await screen.findByRole('radio', { name: /Subscribed calendar/ }));

    expect(await screen.findByRole('link', { name: 'Add to Google Calendar' })).toHaveAttribute('href', feed.googleUrl);
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('6 upcoming invitations'));
    expect(updateCalendarDelivery).toHaveBeenCalledWith('FEED');
    expect(screen.getByRole('link', { name: 'Open in Apple Calendar or Outlook' })).toHaveAttribute('href', feed.webcalUrl);
    expect(screen.getByText('No calendar app has checked this link yet.')).toBeInTheDocument();
  });

  it('keeps the current choice when the switch is not confirmed', async () => {
    vi.mocked(window.confirm).mockReturnValue(false);
    fetchCalendarDelivery.mockResolvedValue(emailState);
    previewCalendarDeliverySwitch.mockResolvedValue({ invitationsToCancel: 2, invitationsToSend: 0 });
    render(<CalendarDeliverySection onVerifyEmail={() => {}} />);

    fireEvent.click(await screen.findByRole('radio', { name: /Subscribed calendar/ }));

    await waitFor(() => expect(window.confirm).toHaveBeenCalled());
    expect(updateCalendarDelivery).not.toHaveBeenCalled();
    expect(screen.getByRole('radio', { name: /Email invitations/ })).toBeChecked();
  });

  it('switches without asking when nothing would be sent or cancelled', async () => {
    fetchCalendarDelivery.mockResolvedValue({ delivery: 'NONE', emailAvailable: false, feed: null });
    previewCalendarDeliverySwitch.mockResolvedValue({ invitationsToCancel: 0, invitationsToSend: 0 });
    updateCalendarDelivery.mockResolvedValue(feedState);
    render(<CalendarDeliverySection onVerifyEmail={() => {}} />);

    fireEvent.click(await screen.findByRole('radio', { name: /Subscribed calendar/ }));

    expect(await screen.findByText(feed.url)).toBeInTheDocument();
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it('disables invitations without a verified email and links to verifying it', async () => {
    const onVerifyEmail = vi.fn();
    fetchCalendarDelivery.mockResolvedValue({ delivery: 'NONE', emailAvailable: false, feed: null });
    render(<CalendarDeliverySection onVerifyEmail={onVerifyEmail} />);

    expect(await screen.findByRole('radio', { name: /Email invitations/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Verify your email' }));
    expect(onVerifyEmail).toHaveBeenCalled();
  });

  it('shows when a calendar app last fetched the feed', async () => {
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60_000).toISOString();
    fetchCalendarDelivery.mockResolvedValue({ ...feedState, feed: { ...feed, lastFetchedAt: fiveMinutesAgo } });
    render(<CalendarDeliverySection onVerifyEmail={() => {}} />);

    expect(await screen.findByText('A calendar app last checked for updates 5m ago.')).toBeInTheDocument();
  });
});

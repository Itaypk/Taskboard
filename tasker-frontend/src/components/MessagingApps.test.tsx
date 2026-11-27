import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { LINK_POLL_MS, MessagingApps } from './MessagingApps';

const { fetchChannelLinks, startChannelLink, unlinkChannel } = vi.hoisted(() => ({
  fetchChannelLinks: vi.fn(),
  startChannelLink: vi.fn(),
  unlinkChannel: vi.fn(),
}));

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, fetchChannelLinks, startChannelLink, unlinkChannel };
});

const notLinked = { channels: [{ channel: 'whatsapp', offered: true, link: null }] };
const linked = {
  channels: [{ channel: 'whatsapp', offered: true, link: { display: '•••• 0978', linkedAt: '2026-11-27T10:00:00Z' } }],
};
const code = {
  code: 'AB2C-D3EF',
  expiresAt: '2026-11-27T10:15:00Z',
  url: 'https://wa.me/15550001111?text=AB2C-D3EF',
};

describe('MessagingApps', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders nothing when the instance offers no messaging app', async () => {
    fetchChannelLinks.mockResolvedValue({ channels: [] });
    const { container } = render(<MessagingApps />);
    await act(async () => {});
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a code and a link to the app, then the account once the code arrives', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-27T10:00:00Z') });
    fetchChannelLinks.mockResolvedValueOnce(notLinked).mockResolvedValue(linked);
    startChannelLink.mockResolvedValue(code);
    render(<MessagingApps />);

    fireEvent.click(await screen.findByRole('button', { name: 'Connect' }));

    expect(await screen.findByText('AB2C-D3EF')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open WhatsApp' })).toHaveAttribute('href', code.url);

    await act(async () => { await vi.advanceTimersByTimeAsync(LINK_POLL_MS); });

    expect(await screen.findByText('Connected · •••• 0978')).toBeInTheDocument();
    expect(screen.queryByText('AB2C-D3EF')).not.toBeInTheDocument();
  });

  it('says so when the code expires before it arrives', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-27T10:14:59Z') });
    fetchChannelLinks.mockResolvedValue(notLinked);
    startChannelLink.mockResolvedValue(code);
    render(<MessagingApps />);

    fireEvent.click(await screen.findByRole('button', { name: 'Connect' }));
    await screen.findByText('AB2C-D3EF');
    await act(async () => { await vi.advanceTimersByTimeAsync(LINK_POLL_MS); });

    expect(await screen.findByText('The code expired before it arrived.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Get a new code' })).toBeInTheDocument();
  });

  it('explains when the account has no sign-in method yet', async () => {
    fetchChannelLinks.mockResolvedValue(notLinked);
    startChannelLink.mockRejectedValue(new ApiError({ status: 403, statusText: 'Forbidden', path: '/api/v1/channel-links/whatsapp/token', userMessage: 'x' }));
    render(<MessagingApps />);

    fireEvent.click(await screen.findByRole('button', { name: 'Connect' }));

    expect(await screen.findByText(/Add a sign-in method/)).toBeInTheDocument();
  });

  it('disconnects after confirming', async () => {
    fetchChannelLinks.mockResolvedValueOnce(linked).mockResolvedValue(notLinked);
    unlinkChannel.mockResolvedValue(undefined);
    render(<MessagingApps />);

    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect' }));

    expect(await screen.findByRole('button', { name: 'Connect' })).toBeInTheDocument();
    expect(unlinkChannel).toHaveBeenCalledWith('whatsapp');
    expect(window.confirm).toHaveBeenCalled();
  });

  it('only offers to disconnect a linked app the instance no longer runs', async () => {
    fetchChannelLinks.mockResolvedValue({ channels: [{ ...linked.channels[0], offered: false }] });
    render(<MessagingApps />);

    expect(await screen.findByRole('button', { name: 'Disconnect' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Connect' })).not.toBeInTheDocument();
  });
});

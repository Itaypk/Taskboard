import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LinkedIdentity } from '../auth/authApi';

const { fetchIdentities, fetchTelegramBot, auth } = vi.hoisted(() => ({
    fetchIdentities: vi.fn(),
    fetchTelegramBot: vi.fn(),
    // Only the field this component reads; the rest of AuthUser is irrelevant here.
    auth: { telegramChatReady: false, refresh: vi.fn() },
}));

vi.mock('../auth/authApi', async () => {
    const actual = await vi.importActual<typeof import('../auth/authApi')>('../auth/authApi');
    return { ...actual, fetchIdentities, fetchTelegramBot, fetchMe: vi.fn(), unlinkIdentity: vi.fn() };
});

vi.mock('../auth/AuthContext', () => ({
    useAuth: () => ({
        state: { status: 'authenticated', user: { telegramChatReady: auth.telegramChatReady } },
        setUser: vi.fn(),
        refresh: auth.refresh,
    }),
}));

import { ConnectedAccounts } from './ConnectedAccounts';

const identity = (provider: string): LinkedIdentity => ({
    provider,
    linkedAt: '2026-09-17T13:23:08Z',
    lastLoginAt: '2026-09-17T13:23:08Z',
});

/** The SPA reads the link outcome off the query string the callback redirected to. */
function atSettings(query = '') {
    window.history.replaceState(null, '', `/settings${query}`);
}

beforeEach(() => {
    vi.clearAllMocks();
    fetchTelegramBot.mockResolvedValue({ username: 'BacklogFyiBot' });
    auth.telegramChatReady = false;
    atSettings();
});
afterEach(cleanup);

describe('ConnectedAccounts', () => {
    /**
     * The link itself is not enough — Telegram won't let the bot write to a chat the user has
     * never opened, so a link that ends silently leaves the weekly planning conversation
     * permanently undeliverable. This panel is the only thing that tells them.
     */
    it('walks the user into the bot chat after a successful link', async () => {
        fetchIdentities.mockResolvedValue([identity('email'), identity('telegram')]);
        atSettings('?telegramLink=success');

        render(<ConnectedAccounts />);

        expect(await screen.findByRole('status')).toHaveTextContent(/open the chat with the bot/i);
        expect(await screen.findByRole('link', { name: 'Open the chat with @BacklogFyiBot' }))
            .toHaveAttribute('href', 'https://t.me/BacklogFyiBot');
    });

    it('skips the next step when the chat was already open before linking', async () => {
        // The post-link welcome got through, so the server already knows the bot can reach them.
        fetchIdentities.mockResolvedValue([identity('telegram')]);
        auth.telegramChatReady = true;
        atSettings('?telegramLink=success');

        render(<ConnectedAccounts />);

        expect(await screen.findByRole('link', { name: 'Open the chat with @BacklogFyiBot' })).toBeInTheDocument();
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });

    it('keeps the panel up after the notice is scrubbed from the URL', async () => {
        fetchIdentities.mockResolvedValue([identity('telegram')]);
        atSettings('?telegramLink=success');

        render(<ConnectedAccounts />);

        await screen.findByRole('status');
        // The component rewrites the URL on mount; the panel must survive that and the
        // re-renders that follow the identities and bot-handle loads.
        expect(window.location.search).toBe('');
        await waitFor(() => expect(fetchTelegramBot).toHaveBeenCalled());
        expect(screen.getByRole('status')).toBeInTheDocument();
    });

    it('keeps asking a linked user to open the chat on later visits until the bot can reach them', async () => {
        fetchIdentities.mockResolvedValue([identity('email'), identity('telegram')]);

        render(<ConnectedAccounts />);

        expect(await screen.findByRole('status')).toHaveTextContent(/open the chat with the bot/i);
    });

    it('re-checks reachability when the user comes back from Telegram', async () => {
        fetchIdentities.mockResolvedValue([identity('telegram')]);

        render(<ConnectedAccounts />);
        await screen.findByRole('status');
        fireEvent.focus(window);

        expect(auth.refresh).toHaveBeenCalled();
    });

    it('offers only a quiet chat link once the bot can reach the user', async () => {
        fetchIdentities.mockResolvedValue([identity('email'), identity('telegram')]);
        auth.telegramChatReady = true;

        render(<ConnectedAccounts />);

        expect(await screen.findByRole('link', { name: 'Open the chat with @BacklogFyiBot' }))
            .toHaveAttribute('href', 'https://t.me/BacklogFyiBot');
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });

    it('does not ask for the bot handle when no Telegram account is linked', async () => {
        fetchIdentities.mockResolvedValue([identity('email')]);

        render(<ConnectedAccounts />);

        expect(await screen.findByRole('link', { name: 'Link Telegram' })).toBeInTheDocument();
        expect(fetchTelegramBot).not.toHaveBeenCalled();
    });

    it('omits the chat link when the deployment runs no bot', async () => {
        fetchIdentities.mockResolvedValue([identity('telegram')]);
        fetchTelegramBot.mockResolvedValue({ username: null });
        atSettings('?telegramLink=success');

        render(<ConnectedAccounts />);

        await screen.findByRole('status');
        await waitFor(() => expect(fetchTelegramBot).toHaveBeenCalled());
        expect(screen.queryByRole('link', { name: /Open the chat/ })).not.toBeInTheDocument();
    });

    it('still reports a link failure as an error, not as the next-step panel', async () => {
        fetchIdentities.mockResolvedValue([identity('email')]);
        atSettings('?telegramLink=conflict');

        render(<ConnectedAccounts />);

        expect(
            await screen.findByText(
                'That Telegram account is already linked to a different Backlog.fyi account.',
            ),
        ).toBeInTheDocument();
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });
});

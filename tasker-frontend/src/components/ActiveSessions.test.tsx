import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ActiveSession } from '../auth/authApi';

const { fetchSessions, revokeOtherSessions } = vi.hoisted(() => ({
    fetchSessions: vi.fn(),
    revokeOtherSessions: vi.fn(),
}));

vi.mock('../auth/authApi', async () => {
    const actual = await vi.importActual<typeof import('../auth/authApi')>('../auth/authApi');
    return { ...actual, fetchSessions, revokeOtherSessions };
});

import { ActiveSessions } from './ActiveSessions';

const session = (over: Partial<ActiveSession> = {}): ActiveSession => ({
    current: false,
    device: 'Firefox on Linux',
    ipAddress: '203.0.113.7',
    signedInAt: '2026-08-01T09:00:00Z',
    lastActiveAt: '2026-08-20T18:30:00Z',
    ...over,
});

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('ActiveSessions', () => {
    it('lists each session and marks the current device', async () => {
        fetchSessions.mockResolvedValue([
            session({ current: true, device: 'Chrome on macOS', ipAddress: '198.51.100.4' }),
            session(),
        ]);

        render(<ActiveSessions />);

        expect(await screen.findByText(/Chrome on macOS/)).toBeInTheDocument();
        expect(screen.getByText(/Firefox on Linux/)).toBeInTheDocument();
        expect(screen.getByText(/198\.51\.100\.4/)).toBeInTheDocument();
        expect(screen.getAllByText('This device')).toHaveLength(1);
    });

    it('falls back to a placeholder when a session has no recorded device', async () => {
        fetchSessions.mockResolvedValue([session({ current: true, device: null, ipAddress: null })]);

        render(<ActiveSessions />);

        expect(await screen.findByText('Unknown device')).toBeInTheDocument();
    });

    it('disables the button when this is the only session', async () => {
        fetchSessions.mockResolvedValue([session({ current: true })]);

        render(<ActiveSessions />);

        expect(await screen.findByRole('button', { name: 'Sign out everywhere else' })).toBeDisabled();
    });

    it('revokes the other sessions after confirming, then reloads the list', async () => {
        fetchSessions
            .mockResolvedValueOnce([session({ current: true }), session()])
            .mockResolvedValueOnce([session({ current: true })]);
        revokeOtherSessions.mockResolvedValue({ revoked: 1 });

        render(<ActiveSessions />);
        fireEvent.click(await screen.findByRole('button', { name: 'Sign out everywhere else' }));
        // The count in the confirm prompt covers only the other sessions, not this one.
        expect(screen.getByText('Sign out of all other sessions (1)?')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Sign out others' }));

        await waitFor(() => expect(revokeOtherSessions).toHaveBeenCalledTimes(1));
        expect(fetchSessions).toHaveBeenCalledTimes(2);
        await waitFor(() =>
            expect(screen.getByRole('button', { name: 'Sign out everywhere else' })).toBeDisabled(),
        );
    });

    it('surfaces an error when revoking fails', async () => {
        fetchSessions.mockResolvedValue([session({ current: true }), session()]);
        revokeOtherSessions.mockRejectedValue(new Error('boom'));

        render(<ActiveSessions />);
        fireEvent.click(await screen.findByRole('button', { name: 'Sign out everywhere else' }));
        fireEvent.click(screen.getByRole('button', { name: 'Sign out others' }));

        expect(
            await screen.findByText('Could not sign out the other sessions. Please try again.'),
        ).toBeInTheDocument();
    });
});

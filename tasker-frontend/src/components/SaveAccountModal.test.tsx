import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';

const { requestEmailVerification, auth } = vi.hoisted(() => ({
    requestEmailVerification: vi.fn(),
    // Only the field this component reads; the rest of AuthUser is irrelevant here.
    auth: { claimed: false, refresh: vi.fn() },
}));

vi.mock('../api', async () => {
    const actual = await vi.importActual<typeof import('../api')>('../api');
    return { ...actual, requestEmailVerification };
});

vi.mock('../auth/AuthContext', () => ({
    useAuth: () => ({
        state: { status: 'authenticated', user: { claimed: auth.claimed } },
        setUser: vi.fn(),
        refresh: auth.refresh,
    }),
}));

import { SaveAccountModal } from './SaveAccountModal';

function sendEmail(address = 'me@example.com') {
    fireEvent.click(screen.getByRole('button', { name: /continue with email/i }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), { target: { value: address } });
    fireEvent.click(screen.getByRole('button', { name: 'Send confirmation link' }));
}

beforeEach(() => {
    vi.clearAllMocks();
    auth.claimed = false;
});
afterEach(cleanup);

describe('SaveAccountModal', () => {
    it('links Telegram through the same OIDC round-trip as Settings', () => {
        render(<SaveAccountModal open onClose={vi.fn()} />);

        expect(screen.getByRole('link', { name: /continue with telegram/i }))
            .toHaveAttribute('href', '/api/auth/telegram/link/start');
    });

    it('sends a verification link without the global error toast, then waits for it', async () => {
        requestEmailVerification.mockResolvedValue(undefined);
        render(<SaveAccountModal open onClose={vi.fn()} />);

        sendEmail();

        expect(await screen.findByRole('status')).toHaveTextContent('me@example.com');
        // The modal shows its own message for failures, so the request must not also toast.
        expect(requestEmailVerification).toHaveBeenCalledWith('me@example.com', { emitErrors: false });

        // The link usually opens in another tab; coming back re-checks whether the account is claimed.
        act(() => { window.dispatchEvent(new Event('focus')); });
        expect(auth.refresh).toHaveBeenCalled();
    });

    /**
     * The address already belongs to another account. The server refuses rather than merge, and
     * the user has to know this board won't move over: otherwise "sign in with that email" quietly
     * strands everything they did in the demo.
     */
    it('explains an email that already has an account', async () => {
        requestEmailVerification.mockRejectedValue(
            new ApiError({ status: 409, statusText: 'Conflict', path: '/api/v1/settings/email', userMessage: 'x' }),
        );
        render(<SaveAccountModal open onClose={vi.fn()} />);

        sendEmail();

        expect(await screen.findByRole('alert')).toHaveTextContent(/already has its own account/i);
    });

    it('names a blocked email domain', async () => {
        requestEmailVerification.mockRejectedValue(
            new ApiError({ status: 400, statusText: 'Bad Request', path: '', userMessage: 'x', code: 'BLOCKED_EMAIL_DOMAIN' }),
        );
        render(<SaveAccountModal open onClose={vi.fn()} />);

        sendEmail('me@mailinator.com');

        expect(await screen.findByRole('alert')).toHaveTextContent(/email provider isn.t supported/i);
    });

    it('confirms once the account is claimed', () => {
        auth.claimed = true;
        const onClose = vi.fn();
        render(<SaveAccountModal open onClose={onClose} />);

        expect(screen.getByRole('status')).toHaveTextContent(/your account is saved/i);
        fireEvent.click(screen.getByRole('button', { name: 'Done' }));
        expect(onClose).toHaveBeenCalled();
    });
});

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicConfig } from './authApi';

const { fetchPublicConfig, passwordLogin, setUser } = vi.hoisted(() => ({
    fetchPublicConfig: vi.fn(),
    passwordLogin: vi.fn(),
    setUser: vi.fn(),
}));

vi.mock('./authApi', async () => {
    const actual = await vi.importActual<typeof import('./authApi')>('./authApi');
    return { ...actual, fetchPublicConfig, passwordLogin };
});

vi.mock('./AuthContext', () => ({
    useAuth: () => ({ state: { status: 'unauthenticated' }, setUser, refresh: vi.fn() }),
}));

import { ApiError } from '../api';
import { LoginPage } from './LoginPage';

const selfHosted: PublicConfig = {
    login: { telegram: false, email: false, password: true, demo: false },
    registrationOpen: false,
};

function renderPage() {
    render(
        <MemoryRouter>
            <LoginPage />
        </MemoryRouter>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState(null, '', '/');
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe('LoginPage', () => {
    it('shows only the methods the instance offers', async () => {
        fetchPublicConfig.mockResolvedValue(selfHosted);
        renderPage();

        // Without the sandbox, sign-in becomes the primary call-to-action.
        const signIn = await screen.findByRole('button', { name: /^Sign in/ });
        expect(screen.queryByRole('button', { name: /no sign-up/ })).not.toBeInTheDocument();

        fireEvent.click(signIn);

        expect(screen.getByLabelText('Username')).toBeInTheDocument();
        expect(screen.getByLabelText('Password')).toBeInTheDocument();
        expect(screen.queryByRole('link', { name: /Telegram/ })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /Continue with email/ })).not.toBeInTheDocument();
        expect(screen.getByText('Sign in with an existing account.')).toBeInTheDocument();
    });

    it('signs in with a username and password', async () => {
        fetchPublicConfig.mockResolvedValue(selfHosted);
        const user = { id: 'u1' };
        passwordLogin.mockResolvedValue(user);
        renderPage();

        fireEvent.click(await screen.findByRole('button', { name: /^Sign in/ }));
        fireEvent.change(screen.getByLabelText('Username'), { target: { value: ' alice ' } });
        fireEvent.change(screen.getByLabelText('Password'), { target: { value: 's3cret' } });
        fireEvent.submit(screen.getByLabelText('Password').closest('form')!);

        await waitFor(() => expect(setUser).toHaveBeenCalledWith(user));
        expect(passwordLogin).toHaveBeenCalledWith('alice', 's3cret');
    });

    it('names a wrong password', async () => {
        fetchPublicConfig.mockResolvedValue(selfHosted);
        passwordLogin.mockRejectedValue(new ApiError({
            status: 400, statusText: 'Bad Request', path: '/api/auth/password', userMessage: 'x', code: 'INVALID_CREDENTIALS',
        }));
        renderPage();

        fireEvent.click(await screen.findByRole('button', { name: /^Sign in/ }));
        fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
        fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'nope' } });
        fireEvent.submit(screen.getByLabelText('Password').closest('form')!);

        expect(await screen.findByText('Wrong username or password.')).toBeInTheDocument();
        expect(setUser).not.toHaveBeenCalled();
    });

    it('keeps the hosted landing page when the config cannot be loaded', async () => {
        fetchPublicConfig.mockRejectedValue(new Error('offline'));
        vi.spyOn(console, 'error').mockImplementation(() => {});
        renderPage();

        await waitFor(() => expect(fetchPublicConfig).toHaveBeenCalled());
        expect(screen.getByRole('button', { name: /no sign-up/ })).toBeInTheDocument();
    });
});

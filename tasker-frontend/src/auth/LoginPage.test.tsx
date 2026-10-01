import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicConfig } from '../publicConfig';

const { passwordLogin, setUser } = vi.hoisted(() => ({
    passwordLogin: vi.fn(),
    setUser: vi.fn(),
}));

vi.mock('./authApi', async () => {
    const actual = await vi.importActual<typeof import('./authApi')>('./authApi');
    return { ...actual, passwordLogin };
});

vi.mock('./AuthContext', () => ({
    useAuth: () => ({ state: { status: 'unauthenticated' }, setUser, refresh: vi.fn() }),
}));

import { ApiError } from '../api';
import { resetPublicConfigForTests } from '../publicConfig';
import { LoginPage } from './LoginPage';

const selfHosted: PublicConfig = {
    login: { telegram: false, email: false, password: true, demo: false },
    registrationOpen: false,
    branding: { name: 'Acme Tasks', supportEmail: 'help@acme.test', abuseEmail: 'abuse@acme.test' },
};

/** What IndexHtmlController inlines into the served page. */
function withInlineConfig(config: PublicConfig | string) {
    const script = document.createElement('script');
    script.id = 'app-config';
    script.type = 'application/json';
    script.textContent = typeof config === 'string' ? config : JSON.stringify(config);
    document.head.appendChild(script);
}

function renderPage() {
    render(
        <MemoryRouter>
            <LoginPage />
        </MemoryRouter>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    resetPublicConfigForTests();
    window.history.replaceState(null, '', '/');
});
afterEach(() => {
    cleanup();
    document.getElementById('app-config')?.remove();
    vi.restoreAllMocks();
});

describe('LoginPage', () => {
    it('shows only the methods the instance offers', async () => {
        withInlineConfig(selfHosted);
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

    it('shows the instance name from the first render', () => {
        withInlineConfig(selfHosted);
        renderPage();

        expect(screen.getByText('Acme Tasks')).toBeInTheDocument();
        expect(screen.queryByText('Backlog.fyi')).not.toBeInTheDocument();
    });

    it('signs in with a username and password', async () => {
        withInlineConfig(selfHosted);
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
        withInlineConfig(selfHosted);
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

    it('keeps the hosted landing page when there is no usable inline config', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
        withInlineConfig('{{json config}}');
        renderPage();

        expect(screen.getByRole('button', { name: /no sign-up/ })).toBeInTheDocument();
        expect(screen.getByText('Backlog.fyi')).toBeInTheDocument();
    });
});

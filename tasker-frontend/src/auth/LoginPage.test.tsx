import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicConfig } from '../publicConfig';

const { demoLogin, passwordLogin, setUser } = vi.hoisted(() => ({
    demoLogin: vi.fn(),
    passwordLogin: vi.fn(),
    setUser: vi.fn(),
}));

vi.mock('./authApi', async () => {
    const actual = await vi.importActual<typeof import('./authApi')>('./authApi');
    return { ...actual, demoLogin, passwordLogin };
});

vi.mock('./AuthContext', () => ({
    useAuth: () => ({ state: { status: 'unauthenticated' }, setUser, refresh: vi.fn() }),
}));

import { ApiError } from '../api';
import { resetPublicConfigForTests } from '../publicConfig';
import { LoginPage } from './LoginPage';

const selfHosted: PublicConfig = {
    login: { telegram: false, google: false, email: false, password: true, demo: false },
    registrationOpen: false,
    branding: { name: 'Acme Tasks', supportEmail: 'help@acme.test', abuseEmail: 'abuse@acme.test' },
    ai: false,
    aiZeroDataRetention: false,
    inboundMail: false,
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
        expect(screen.queryByRole('link', { name: /Google/ })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /Continue with email/ })).not.toBeInTheDocument();
        expect(screen.getByText('Sign in with an existing account.')).toBeInTheDocument();
    });

    it('offers Google sign-in when configured, carrying the registration hints', async () => {
        withInlineConfig({ ...selfHosted, login: { ...selfHosted.login, google: true } });
        renderPage();

        fireEvent.click(await screen.findByRole('button', { name: /^Sign in/ }));

        const google = screen.getByRole('link', { name: 'Continue with Google' });
        expect(google.getAttribute('href')).toMatch(/^\/api\/auth\/google\/start\?.*lang=en/);
    });

    it('opens with the notice when Google sign-in hit an existing email', () => {
        window.history.replaceState(null, '', '/?googleLogin=emailConflict');
        withInlineConfig(selfHosted);
        renderPage();

        expect(screen.getByRole('alert')).toHaveTextContent('An account with this email address already exists.');
        expect(window.location.search).toBe('');
    });

    it('shows the instance name from the first render', () => {
        withInlineConfig(selfHosted);
        renderPage();

        expect(screen.getByText('Acme Tasks')).toBeInTheDocument();
        expect(screen.queryByText('Backlog.fyi')).not.toBeInTheDocument();
    });

    it('claims zero-data-retention AI only when the instance enforces it', () => {
        withInlineConfig(selfHosted);
        renderPage();
        expect(screen.getByText('No ads. No trackers. Encrypted at rest.')).toBeInTheDocument();
        cleanup();
        document.getElementById('app-config')?.remove();
        resetPublicConfigForTests();

        withInlineConfig({ ...selfHosted, aiZeroDataRetention: true });
        renderPage();
        expect(screen.getByText('No ads. No trackers. Encrypted at rest. ZDR AI.')).toBeInTheDocument();
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

        expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password.');
        expect(setUser).not.toHaveBeenCalled();
    });

    it('moves focus into the named dialog and back to the opener on Escape', async () => {
        withInlineConfig(selfHosted);
        renderPage();

        const signIn = await screen.findByRole('button', { name: /^Sign in/ });
        signIn.focus();
        fireEvent.click(signIn);

        const dialog = screen.getByRole('dialog', { name: 'Pick how you\'d like to continue' });
        expect(dialog).toContainElement(document.activeElement as HTMLElement);

        fireEvent.keyDown(window, { key: 'Escape' });
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(signIn).toHaveFocus();
    });

    it('keeps Tab inside the dialog', async () => {
        withInlineConfig(selfHosted);
        renderPage();
        fireEvent.click(await screen.findByRole('button', { name: /^Sign in/ }));

        const dialog = screen.getByRole('dialog');
        const close = screen.getByRole('button', { name: 'Close' });
        const controls = dialog.querySelectorAll<HTMLElement>('a[href], button, input');
        const last = controls[controls.length - 1];

        last.focus();
        fireEvent.keyDown(dialog, { key: 'Tab' });
        expect(close).toHaveFocus();

        fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
        expect(last).toHaveFocus();
    });

    it('announces a failed sandbox start even with the dialog closed', async () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
        demoLogin.mockRejectedValue(new Error('boom'));
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: /no sign-up/ }));

        expect(await screen.findByRole('alert')).toBeInTheDocument();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('keeps the hosted landing page when there is no usable inline config', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
        withInlineConfig('{{json config}}');
        renderPage();

        expect(screen.getByRole('button', { name: /no sign-up/ })).toBeInTheDocument();
        expect(screen.getByText('Backlog.fyi')).toBeInTheDocument();
    });
});

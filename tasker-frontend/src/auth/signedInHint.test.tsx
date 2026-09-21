import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { UNAUTHENTICATED_EVENT } from '../api';
import { AuthProvider, useAuth } from './AuthContext';
import { wasSignedIn } from './signedInHint';

const fetchMe = vi.fn();
vi.mock('./authApi', () => ({
    fetchMe: () => fetchMe(),
    logout: () => Promise.resolve(),
}));
vi.mock('../i18n', () => ({ applyLocale: () => Promise.resolve() }));

const user = { id: 'u1', preferredLanguage: 'en' };

function Probe() {
    const { state, signOut } = useAuth();
    return <button onClick={() => void signOut()}>{state.status}</button>;
}

const renderProvider = () => render(<AuthProvider><Probe /></AuthProvider>);

beforeEach(() => {
    localStorage.clear();
    fetchMe.mockReset();
});
afterEach(() => localStorage.clear());

describe('signed-in hint', () => {
    it('is set once /me resolves to a user', async () => {
        fetchMe.mockResolvedValue(user);
        renderProvider();

        expect(await screen.findByText('authenticated')).toBeInTheDocument();
        expect(wasSignedIn()).toBe(true);
    });

    it('is cleared when /me reports nobody', async () => {
        localStorage.setItem('backlog.signedIn', '1');
        fetchMe.mockResolvedValue(null);
        renderProvider();

        expect(await screen.findByText('unauthenticated')).toBeInTheDocument();
        expect(wasSignedIn()).toBe(false);
    });

    // The path that would otherwise leak: a session expiring mid-use never goes through signOut.
    it('is cleared by the 401 event api.ts raises', async () => {
        fetchMe.mockResolvedValue(user);
        renderProvider();
        expect(await screen.findByText('authenticated')).toBeInTheDocument();

        await act(async () => { window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT)); });

        expect(screen.getByText('unauthenticated')).toBeInTheDocument();
        expect(wasSignedIn()).toBe(false);
    });

    it('is cleared on sign-out', async () => {
        fetchMe.mockResolvedValue(user);
        renderProvider();
        const button = await screen.findByText('authenticated');

        await act(async () => { button.click(); });

        expect(screen.getByText('unauthenticated')).toBeInTheDocument();
        expect(wasSignedIn()).toBe(false);
    });

    it('treats unavailable storage as not signed in', () => {
        const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('site data blocked');
        });
        expect(wasSignedIn()).toBe(false);
        getItem.mockRestore();
    });
});

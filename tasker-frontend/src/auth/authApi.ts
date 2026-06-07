import { request } from '../api';

export interface AuthUser {
    id: string;
    telegramId: number | null;
    telegramUsername: string | null;
    telegramFirstName: string | null;
    telegramPhotoUrl: string | null;
    email: string | null;
}

export interface TelegramWidgetPayload {
    id: number;
    first_name?: string;
    last_name?: string;
    username?: string;
    photo_url?: string;
    auth_date: number;
    hash: string;
}

export const fetchMe = (): Promise<AuthUser | null> =>
    request<AuthUser | null>('/api/auth/me');

export const telegramLogin = (payload: TelegramWidgetPayload): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/telegram', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });

export const devLogin = (): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/dev-login', { method: 'POST' });

export const demoLogin = (): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/demo-login', { method: 'POST' });

// Passwordless email login: sends a magic link. Always resolves (the backend never
// reveals whether the address maps to an account). The link itself logs the user in.
export const requestEmailLogin = (email: string): Promise<void> =>
    request<void>('/api/auth/email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
    });

export const logout = (): Promise<void> =>
    request<void>('/api/auth/logout', { method: 'POST' });

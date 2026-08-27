import { request } from '../api';

export interface AuthUser {
    id: string;
    telegramId: number | null;
    telegramUsername: string | null;
    telegramFirstName: string | null;
    telegramPhotoUrl: string | null;
    email: string | null;
    /** False while the account has no login identity yet — drives the "save your account" nudge. */
    claimed: boolean;
    /** Stored UI-language preference (e.g. `en-US`, `he`); drives the i18n locale on boot (docs/I18N.md). */
    preferredLanguage: string;
}

export const fetchMe = (): Promise<AuthUser | null> =>
    request<AuthUser | null>('/api/auth/me');

// Telegram login/link use the OIDC redirect flow: navigate the browser to these backend
// endpoints, which 302 to Telegram and (on the callback) back to the SPA. No fetch/JSON.
export const telegramLoginUrl = (next?: string): string =>
    next && next !== '/'
        ? `/api/auth/telegram/start?next=${encodeURIComponent(next)}`
        : '/api/auth/telegram/start';

export const TELEGRAM_LINK_URL = '/api/auth/telegram/link/start';

export const devLogin = (): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/dev-login', { method: 'POST' });

export const demoLogin = (): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/demo-login', { method: 'POST' });

// Passwordless email login: sends a magic link. Always resolves (the backend never
// reveals whether the address maps to an account). The link itself logs the user in.
// next: optional same-origin path to navigate to after successful login.
export const requestEmailLogin = (email: string, next?: string): Promise<void> =>
    request<void>('/api/auth/email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, next }),
    });

// Side-effect-free check: returns true if the token is still valid (not consumed, not expired).
export const precheckEmailLogin = (token: string): Promise<{ valid: boolean }> =>
    request<{ valid: boolean }>(`/api/auth/email/precheck?token=${encodeURIComponent(token)}`);

// Consume a magic-link token and establish a session.
export type EmailCallbackOutcome = 'success' | 'invalid' | 'unverified';
export const completeEmailLogin = (token: string): Promise<{ outcome: EmailCallbackOutcome }> =>
    request<{ outcome: EmailCallbackOutcome }>('/api/auth/email/callback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
    });

// Consume an email-verification token (unauthenticated — token is the credential).
export const confirmEmailVerification = (token: string): Promise<{ success: boolean }> =>
    request<{ success: boolean }>('/api/v1/settings/email/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
    });

export const logout = (): Promise<void> =>
    request<void>('/api/auth/logout', { method: 'POST' });

export interface LinkedIdentity {
    provider: string;
    linkedAt: string | null;
    lastLoginAt: string | null;
}

export const fetchIdentities = (): Promise<LinkedIdentity[]> =>
    request<LinkedIdentity[]>('/api/auth/identities');

export const unlinkIdentity = (provider: string): Promise<void> =>
    request<void>(`/api/auth/identities/${provider}`, { method: 'DELETE' });

/** One place the account is signed in. Carries no session id — revocation is all-others-at-once. */
export interface ActiveSession {
    current: boolean;
    device: string | null;
    ipAddress: string | null;
    signedInAt: string;
    lastActiveAt: string;
}

export const fetchSessions = (): Promise<ActiveSession[]> =>
    request<ActiveSession[]>('/api/auth/sessions');

export const revokeOtherSessions = (): Promise<{ revoked: number }> =>
    request<{ revoked: number }>('/api/auth/sessions/revoke-others', { method: 'POST' });

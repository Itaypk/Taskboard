import { request } from '../api';
import { getActiveLocale } from '../i18n/format';

export interface AuthUser {
    id: string;
    telegramId: number | null;
    telegramUsername: string | null;
    telegramFirstName: string | null;
    telegramPhotoUrl: string | null;
    /**
     * Whether the bot can message this user unprompted. False for a linked Telegram account whose
     * chat was never opened (Telegram won't let a bot write first), and for no Telegram at all.
     */
    telegramChatReady: boolean;
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
// Login can register an account, so it carries the time zone too (see withRegistrationHints).
export const telegramLoginUrl = (next?: string): string => {
    const params = new URLSearchParams();
    if (next && next !== '/') params.set('next', next);
    const timeZone = browserTimeZone();
    if (timeZone) params.set('tz', timeZone);
    const query = params.toString();
    return query ? `/api/auth/telegram/start?${query}` : '/api/auth/telegram/start';
};

export const TELEGRAM_LINK_URL = '/api/auth/telegram/link/start';

/** The messaging bot's handle, or null when the deployment runs no bot. */
export interface TelegramBotInfo {
    username: string | null;
}

/**
 * Linking Telegram does not open a chat with the bot, and Telegram won't let a bot write to
 * someone who has never written to it first. Settings uses this handle to send a freshly-linked
 * user into the chat so the assistant can actually reach them.
 */
export const fetchTelegramBot = (): Promise<TelegramBotInfo> =>
    request<TelegramBotInfo>('/api/auth/telegram/bot');

export const devLogin = (): Promise<AuthUser> =>
    request<AuthUser>('/api/auth/dev-login', { method: 'POST' });

/**
 * The endpoints below can *create* an account, and the account's stored language drives the
 * seeded backlog, the emails and the Telegram replies. The server otherwise reads `Accept-Language`,
 * which is the browser's opinion, not the visitor's — so anyone who used the anonymous language
 * switcher would get an account in the wrong language and watch the UI snap back on the next `/me`.
 * Appending the language actually being rendered fixes that; the server still validates it against
 * the supported list and falls back to the header.
 */
function withLang(path: string): string {
    const sep = path.includes('?') ? '&' : '?';
    return `${path}${sep}lang=${encodeURIComponent(getActiveLocale())}`;
}

/** The browser's IANA time zone, or undefined when the runtime doesn't report one. */
function browserTimeZone(): string | undefined {
    try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
    } catch {
        return undefined;
    }
}

/**
 * [withLang] plus the browser's time zone, for the requests that can actually register an account:
 * no request carries the zone on its own, and without it a new account starts on UTC, so its digest
 * and reminders fire at the wrong local time. The server drops a zone it doesn't support.
 */
function withRegistrationHints(path: string): string {
    const timeZone = browserTimeZone();
    return timeZone ? `${withLang(path)}&tz=${encodeURIComponent(timeZone)}` : withLang(path);
}

// emitErrors: false — this is the landing page's primary call-to-action, and LoginPage renders
// its own message per status (rate limited vs. at capacity). Without this the generic toast would
// fire alongside it.
export const demoLogin = (): Promise<AuthUser> =>
    request<AuthUser>(withRegistrationHints('/api/auth/demo-login'), { method: 'POST' }, { emitErrors: false });

// Passwordless email login: sends a magic link. Always resolves (the backend never
// reveals whether the address maps to an account). The link itself logs the user in.
// next: optional same-origin path to navigate to after successful login.
export const requestEmailLogin = (email: string, next?: string): Promise<void> =>
    request<void>(withLang('/api/auth/email'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, next }),
    });

// Username/password login for operator-listed users. emitErrors: false — the form shows its own
// message per failure (wrong credentials vs. rate limited).
export const passwordLogin = (username: string, password: string): Promise<AuthUser> =>
    request<AuthUser>(withRegistrationHints('/api/auth/password'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    }, { emitErrors: false });

// Side-effect-free check: returns true if the token is still valid (not consumed, not expired).
export const precheckEmailLogin = (token: string): Promise<{ valid: boolean }> =>
    request<{ valid: boolean }>(`/api/auth/email/precheck?token=${encodeURIComponent(token)}`);

// Consume a magic-link token and establish a session.
export type EmailCallbackOutcome = 'success' | 'invalid' | 'unverified' | 'closed';
export const completeEmailLogin = (token: string): Promise<{ outcome: EmailCallbackOutcome }> =>
    request<{ outcome: EmailCallbackOutcome }>(withRegistrationHints('/api/auth/email/callback'), {
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

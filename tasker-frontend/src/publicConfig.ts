/**
 * Instance settings the SPA needs before (and after) sign-in: which login methods to offer and what
 * the instance calls itself. The server inlines them into `index.html` as
 * `<script id="app-config" type="application/json">` (`IndexHtmlController`), so they're read
 * synchronously at boot — no request on the critical path, and nothing changes after first paint.
 * The same object is also served at `GET /api/public/config` for scripts.
 *
 * Falls back to the hosted instance's values when the block is missing or unparseable: the Vite dev
 * server and unit tests have no server to fill it in.
 */
export interface PublicConfig {
    login: {
        telegram: boolean;
        email: boolean;
        password: boolean;
        demo: boolean;
    };
    /** False when only existing accounts and operator-listed users can sign in. */
    registrationOpen: boolean;
    /** What the instance calls itself, and where users can reach whoever runs it. */
    branding: {
        name: string;
        supportEmail: string;
        abuseEmail: string;
    };
    /** AI is on and every AI call goes only to zero-data-retention endpoints (`TASKER_AI_ZERO_DATA_RETENTION`). */
    aiZeroDataRetention: boolean;
}

export const DEFAULT_PUBLIC_CONFIG: PublicConfig = {
    login: { telegram: true, email: true, password: false, demo: true },
    registrationOpen: true,
    branding: {
        name: 'Backlog.fyi',
        supportEmail: 'hello@backlog.fyi',
        abuseEmail: 'abuse@backlog.fyi',
    },
    aiZeroDataRetention: true,
};

let cached: PublicConfig | null = null;

function readInlineConfig(): PublicConfig {
    const text = document.getElementById('app-config')?.textContent;
    if (!text) return DEFAULT_PUBLIC_CONFIG;
    try {
        const parsed = JSON.parse(text) as PublicConfig | null;
        return parsed ?? DEFAULT_PUBLIC_CONFIG;
    } catch (e) {
        console.error('Unreadable instance config in index.html; showing defaults', e);
        return DEFAULT_PUBLIC_CONFIG;
    }
}

export function getPublicConfig(): PublicConfig {
    cached ??= readInlineConfig();
    return cached;
}

/**
 * Hook form of [getPublicConfig]. The value is fixed for the page's lifetime, so this never
 * re-renders anything; it exists so components read config the same way they read other context.
 */
export function usePublicConfig(): PublicConfig {
    return getPublicConfig();
}

/** The instance's name and contact addresses. */
export function useBranding(): PublicConfig['branding'] {
    return getPublicConfig().branding;
}

/** Tests only: forget the parsed config so the next read sees the current DOM. */
export function resetPublicConfigForTests(): void {
    cached = null;
}

import { useEffect, useSyncExternalStore } from 'react';
import { fetchPublicConfig, type PublicConfig } from './auth/authApi';

/**
 * Instance settings the SPA needs before (and after) sign-in: which login methods to offer and what
 * the instance calls itself. Served at runtime by `GET /api/public/config`, so one build — or one
 * container image — works for every instance.
 *
 * Until the request answers, everything reads the hosted instance's values, so the anonymous first
 * paint doesn't change for the visitors almost all traffic comes from. Fetched once per page load.
 */
export const DEFAULT_PUBLIC_CONFIG: PublicConfig = {
    login: { telegram: true, email: true, password: false, demo: true },
    registrationOpen: true,
    branding: {
        name: 'Backlog.fyi',
        supportEmail: 'hello@backlog.fyi',
        abuseEmail: 'abuse@backlog.fyi',
    },
};

let current: PublicConfig = DEFAULT_PUBLIC_CONFIG;
let loading: Promise<PublicConfig> | null = null;
const listeners = new Set<() => void>();

export function loadPublicConfig(): Promise<PublicConfig> {
    loading ??= fetchPublicConfig()
        .then(config => {
            current = config;
            // index.html's <title> carries the hosted name; a renamed instance replaces it.
            if (config.branding.name !== DEFAULT_PUBLIC_CONFIG.branding.name) {
                document.title = config.branding.name;
            }
            listeners.forEach(notify => notify());
            return config;
        })
        .catch(e => {
            console.error('Could not load instance config; showing defaults', e);
            return current;
        });
    return loading;
}

function subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

export function usePublicConfig(): PublicConfig {
    useEffect(() => { void loadPublicConfig(); }, []);
    return useSyncExternalStore(subscribe, () => current);
}

/** The instance's name and contact addresses. */
export function useBranding(): PublicConfig['branding'] {
    return usePublicConfig().branding;
}

/** Tests only: forget the fetched config so each test starts from the defaults. */
export function resetPublicConfigForTests(): void {
    current = DEFAULT_PUBLIC_CONFIG;
    loading = null;
}

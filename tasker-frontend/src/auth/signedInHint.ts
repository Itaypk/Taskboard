/**
 * A one-bit "this browser was signed in last time" hint, so the board chunk can start downloading
 * in parallel with `GET /api/auth/me` instead of after it.
 *
 * The session cookie is `HttpOnly`, so nothing readable tells the SPA whether this visitor is
 * signed in until `/me` answers — and by then the chunk request is already a serial round trip
 * behind. On a cold phone open that costs 99–164 ms, which after the Suspense fix is the entire
 * remaining gap (`docs/FAST-INITIAL-LOAD.md`). A `<link rel="modulepreload">` would buy the same
 * time but charge every anonymous visitor ~50 kB against the first-paint budget this app
 * deliberately protects; the hint keeps that path untouched.
 *
 * It is a cache, not an authority: nothing is trusted because it is set, the worst case is one
 * wasted chunk fetch, and `AuthContext` rewrites it on every resolved auth state.
 */
const KEY = 'backlog.signedIn';

/** Storage throws in a private window or with site data blocked; absent is the safe answer. */
export function wasSignedIn(): boolean {
    try {
        return localStorage.getItem(KEY) === '1';
    } catch {
        return false;
    }
}

export function rememberSignedIn(signedIn: boolean): void {
    try {
        if (signedIn) {
            localStorage.setItem(KEY, '1');
        } else {
            localStorage.removeItem(KEY);
        }
    } catch {
        /* nothing to do — the hint is an optimization, not state we need */
    }
}

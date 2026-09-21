/**
 * A `React.lazy` stand-in for chunks that land on the signed-in cold-open path.
 *
 * `lazy` + `Suspense` costs a flat 300 ms there. When a Suspense fallback commits, react-dom
 * stamps `globalMostRecentFallbackTime` and then holds the resolved content back by
 * `FALLBACK_THROTTLE_MS` (300) so a spinner can't flash on and off — even when the chunk is
 * already in the module map, because `lazy` suspends on its first render regardless. On `/` that
 * fallback is `RouteFallback`, which is deliberately the same markup as the auth-bootstrap
 * placeholder it replaces, so the flicker the throttle exists to prevent cannot happen and the
 * 300 ms buys nothing. It measured as the single largest item in the cold open — see
 * `docs/FAST-INITIAL-LOAD.md`.
 *
 * Resolving the module ourselves and rendering the placeholder directly keeps the chunk split
 * while never committing a Suspense fallback. Use it only where that matters; `Suspense` remains
 * the right tool for the routes reached by navigation, which are not racing a first paint.
 */
import { useSyncExternalStore, type ComponentType } from 'react';

export interface LazyState<P> {
    component: ComponentType<P> | null;
    /** The chunk request failed. Usually a stale `index.html` naming pre-redeploy asset hashes. */
    failed: boolean;
}

export interface LazyChunk<P> {
    preload: () => void;
    subscribe: (onChange: () => void) => () => void;
    getState: () => LazyState<P>;
}

export function lazyComponent<P>(load: () => Promise<{ default: ComponentType<P> }>): LazyChunk<P> {
    let state: LazyState<P> = { component: null, failed: false };
    let started = false;
    const listeners = new Set<() => void>();

    const settle = (next: LazyState<P>) => {
        state = next;
        listeners.forEach(listener => listener());
    };

    const preload = () => {
        if (started) return;
        started = true;
        load().then(
            module => settle({ component: module.default, failed: false }),
            () => settle({ component: null, failed: true }),
        );
    };

    return {
        preload,
        subscribe: onChange => {
            listeners.add(onChange);
            return () => listeners.delete(onChange);
        },
        getState: () => state,
    };
}

/**
 * Starts the chunk on first render and re-renders when it lands. Calling this mounts the request,
 * so call it from a component that only renders when the chunk is actually wanted — hoisting it
 * above an early return would pull the chunk into an anonymous visitor's load.
 */
export function useLazyComponent<P>(chunk: LazyChunk<P>): LazyState<P> {
    chunk.preload();
    return useSyncExternalStore(chunk.subscribe, chunk.getState, chunk.getState);
}

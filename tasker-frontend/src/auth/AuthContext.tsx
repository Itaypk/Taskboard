import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from 'react';
import { UNAUTHENTICATED_EVENT } from '../api';
import { applyLocale } from '../i18n';
import { type AuthUser, fetchMe, logout as logoutCall } from './authApi';
import { rememberSignedIn } from './signedInHint';

type AuthState =
    | { status: 'loading' }
    | { status: 'authenticated'; user: AuthUser }
    | { status: 'unauthenticated' };

interface AuthContextValue {
    state: AuthState;
    refresh: () => Promise<void>;
    signOut: () => Promise<void>;
    setUser: (user: AuthUser) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
    const [state, setStateDirectly] = useState<AuthState>({ status: 'loading' });

    // Every auth transition funnels through here so the returning-visitor hint can't drift from
    // the real state. There are three ways to become unauthenticated — sign-out, a null or failed
    // `/me`, and the 401 event `api.ts` raises when a session expires mid-use — and a hint left
    // set by the third would cost a wasted board-chunk fetch on every load until the next sign-in.
    const setState = useCallback((next: AuthState) => {
        if (next.status !== 'loading') {
            rememberSignedIn(next.status === 'authenticated');
        }
        setStateDirectly(next);
    }, []);

    const refresh = useCallback(async () => {
        try {
            const user = await fetchMe();
            if (user) {
                void applyLocale(user.preferredLanguage);
                setState({ status: 'authenticated', user });
            } else {
                setState({ status: 'unauthenticated' });
            }
        } catch {
            setState({ status: 'unauthenticated' });
        }
    }, [setState]);

    useEffect(() => {
        let mounted = true;
        fetchMe()
            .then(user => {
                if (!mounted) return;
                if (user) void applyLocale(user.preferredLanguage);
                setState(user ? { status: 'authenticated', user } : { status: 'unauthenticated' });
            })
            .catch(() => { if (mounted) setState({ status: 'unauthenticated' }); });
        const onUnauth = () => setState({ status: 'unauthenticated' });
        window.addEventListener(UNAUTHENTICATED_EVENT, onUnauth);
        return () => {
            mounted = false;
            window.removeEventListener(UNAUTHENTICATED_EVENT, onUnauth);
        };
    }, [setState]);

    const signOut = useCallback(async () => {
        try {
            await logoutCall();
        } catch {
            /* ignore — we're logging out anyway */
        }
        setState({ status: 'unauthenticated' });
    }, [setState]);

    const setUser = useCallback((user: AuthUser) => {
        void applyLocale(user.preferredLanguage);
        setState({ status: 'authenticated', user });
    }, [setState]);

    const value = useMemo(
        () => ({ state, refresh, signOut, setUser }),
        [state, refresh, signOut, setUser],
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
    return ctx;
}

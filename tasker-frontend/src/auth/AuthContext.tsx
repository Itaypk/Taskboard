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
import { type AuthUser, fetchMe, logout as logoutCall } from './authApi';

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
    const [state, setState] = useState<AuthState>({ status: 'loading' });

    const refresh = useCallback(async () => {
        try {
            const user = await fetchMe();
            setState({ status: 'authenticated', user });
        } catch {
            setState({ status: 'unauthenticated' });
        }
    }, []);

    useEffect(() => {
        refresh();
        const onUnauth = () => setState({ status: 'unauthenticated' });
        window.addEventListener(UNAUTHENTICATED_EVENT, onUnauth);
        return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onUnauth);
    }, [refresh]);

    const signOut = useCallback(async () => {
        try {
            await logoutCall();
        } catch {
            /* ignore — we're logging out anyway */
        }
        setState({ status: 'unauthenticated' });
    }, []);

    const setUser = useCallback((user: AuthUser) => {
        setState({ status: 'authenticated', user });
    }, []);

    const value = useMemo(
        () => ({ state, refresh, signOut, setUser }),
        [state, refresh, signOut, setUser],
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
    return ctx;
}

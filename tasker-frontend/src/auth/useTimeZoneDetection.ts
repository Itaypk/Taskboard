import { useEffect, useRef } from 'react';
import { reportDetectedTimeZone } from '../api';
import { useAuth } from './AuthContext';

/**
 * A new account starts on UTC: no registration request carries the visitor's zone, and the
 * Telegram and email flows reach the server from outside the SPA. So on the first signed-in load
 * the SPA reports the browser's zone instead, and the server applies it once (it ignores the
 * report after the user has saved settings, and keeps UTC for a zone it doesn't support).
 */
export function useTimeZoneDetection(): void {
    const { state, setUser } = useAuth();
    const user = state.status === 'authenticated' ? state.user : null;
    const pending = user?.timeZoneDetectionPending ?? false;
    // StrictMode runs effects twice; the server tolerates a repeat, but there's no need to send one.
    const sent = useRef(false);

    useEffect(() => {
        if (!pending || !user || sent.current) return;
        const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (!timeZone) return;
        sent.current = true;
        reportDetectedTimeZone(timeZone)
            .then(() => setUser({ ...user, timeZoneDetectionPending: false }))
            .catch(e => console.error('Failed to report detected time zone', e));
    }, [pending, user, setUser]);
}

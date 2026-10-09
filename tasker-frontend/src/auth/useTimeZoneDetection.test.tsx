import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { reportDetectedTimeZone, setUser, auth } = vi.hoisted(() => ({
    reportDetectedTimeZone: vi.fn(),
    setUser: vi.fn(),
    auth: { user: { id: 'u1', timeZoneDetectionPending: true } as { id: string; timeZoneDetectionPending: boolean } | null },
}));

vi.mock('../api', () => ({ reportDetectedTimeZone }));

vi.mock('./AuthContext', () => ({
    useAuth: () => ({
        state: auth.user ? { status: 'authenticated', user: auth.user } : { status: 'unauthenticated' },
        setUser,
    }),
}));

import { useTimeZoneDetection } from './useTimeZoneDetection';

const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

beforeEach(() => {
    vi.clearAllMocks();
    reportDetectedTimeZone.mockResolvedValue(undefined);
    auth.user = { id: 'u1', timeZoneDetectionPending: true };
});

afterEach(cleanup);

describe('useTimeZoneDetection', () => {
    it('reports the browser zone once for a pending account and clears the flag locally', async () => {
        const { rerender } = renderHook(() => useTimeZoneDetection());
        rerender();

        await waitFor(() => expect(setUser).toHaveBeenCalledWith({ id: 'u1', timeZoneDetectionPending: false }));
        expect(reportDetectedTimeZone).toHaveBeenCalledTimes(1);
        expect(reportDetectedTimeZone).toHaveBeenCalledWith(browserZone);
    });

    it('does nothing when detection is not pending', () => {
        auth.user = { id: 'u1', timeZoneDetectionPending: false };
        renderHook(() => useTimeZoneDetection());

        expect(reportDetectedTimeZone).not.toHaveBeenCalled();
    });

    it('does nothing when signed out', () => {
        auth.user = null;
        renderHook(() => useTimeZoneDetection());

        expect(reportDetectedTimeZone).not.toHaveBeenCalled();
    });
});

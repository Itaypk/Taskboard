import { afterEach, describe, expect, it, vi } from 'vitest';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../api', () => ({ request }));

import { demoLogin, telegramLoginUrl } from './authApi';

const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;

afterEach(() => vi.restoreAllMocks());

describe('registration hints', () => {
    it('sends the browser time zone with the demo signup', async () => {
        request.mockResolvedValue({});
        await demoLogin();

        const url = new URL(request.mock.calls[0][0], 'https://example.test');
        expect(url.pathname).toBe('/api/auth/demo-login');
        expect(url.searchParams.get('tz')).toBe(zone);
        expect(url.searchParams.get('lang')).toBeTruthy();
    });

    it('carries the time zone and next path on the Telegram login URL', () => {
        const url = new URL(telegramLoginUrl('/settings'), 'https://example.test');

        expect(url.pathname).toBe('/api/auth/telegram/start');
        expect(url.searchParams.get('next')).toBe('/settings');
        expect(url.searchParams.get('tz')).toBe(zone);
    });

    it('omits the time zone when the browser reports none', () => {
        vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions')
            .mockReturnValue({ timeZone: '' } as Intl.ResolvedDateTimeFormatOptions);

        expect(telegramLoginUrl()).toBe('/api/auth/telegram/start');
    });
});

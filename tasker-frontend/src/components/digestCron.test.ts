import { describe, expect, it } from 'vitest';
import { composeDigestCron, parseDigestCron } from './digestCron';

describe('digest cron', () => {
  it('parses the every-day default', () => {
    expect(parseDigestCron('0 0 8 * * *')).toEqual({
      days: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'],
      time: '08:00',
    });
  });

  it('expands ranges and lists, in week order', () => {
    expect(parseDigestCron('0 30 7 * * sun,MON-WED')).toEqual({
      days: ['MON', 'TUE', 'WED', 'SUN'],
      time: '07:30',
    });
  });

  it('falls back to the default for anything it cannot read', () => {
    expect(parseDigestCron('nonsense').time).toBe('08:00');
    expect(parseDigestCron('0 0 8 * * FUNDAY').days).toHaveLength(7);
    expect(parseDigestCron(null).days).toHaveLength(7);
  });

  it('composes every day as a wildcard and a subset as a list', () => {
    expect(composeDigestCron({ days: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'], time: '08:00' }))
      .toBe('0 0 8 * * *');
    expect(composeDigestCron({ days: ['FRI', 'MON'], time: '21:05' })).toBe('0 5 21 * * MON,FRI');
  });

  it('round-trips', () => {
    const cron = '0 45 6 * * MON,TUE,WED,THU,FRI';
    expect(composeDigestCron(parseDigestCron(cron))).toBe(cron);
  });
});

import { describe, expect, it } from 'vitest';
import { formatRelative } from './relativeTime';

describe('formatRelative', () => {
  const now = Date.parse('2026-11-12T12:00:00Z');

  it('picks a sensible unit', () => {
    expect(formatRelative('2026-11-12T11:59:30Z', 'en', now)).toBe('30 seconds ago');
    expect(formatRelative('2026-11-12T10:00:00Z', 'en', now)).toBe('2 hours ago');
    expect(formatRelative('2026-11-11T12:00:00Z', 'en', now)).toBe('yesterday');
  });

  it('returns null for an unparsable date', () => {
    expect(formatRelative('nope', 'en', now)).toBeNull();
  });
});

import { afterEach, describe, expect, it } from 'vitest';
import { loadTextSize, saveTextSize } from './textSize';

describe('textSize', () => {
  afterEach(() => localStorage.clear());

  it('defaults when nothing or garbage is stored', () => {
    expect(loadTextSize()).toBe('default');
    localStorage.setItem('backlog.textSize', 'huge');
    expect(loadTextSize()).toBe('default');
  });

  it('round-trips a saved size', () => {
    saveTextSize('larger');
    expect(loadTextSize()).toBe('larger');
  });
});

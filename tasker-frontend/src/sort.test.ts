import { describe, it, expect } from 'vitest';
import { sortTasks, isSortMode, type SortMode } from './sort';
import type { Task } from './types';

function task(partial: Partial<Task> & { id: string }): Task {
  return {
    title: partial.id,
    status: 'todo',
    categoryId: 'c1',
    tags: [],
    sortKey: 'm',
    createdAt: '2026-01-01T00:00:00Z',
    ...partial,
  };
}

const ids = (tasks: Task[]) => tasks.map(t => t.id);

describe('sortTasks', () => {
  it('none follows the manual sortKey order', () => {
    const tasks = [task({ id: 'c', sortKey: 'c' }), task({ id: 'a', sortKey: 'a' }), task({ id: 'b', sortKey: 'b' })];
    expect(ids(sortTasks(tasks, 'none'))).toEqual(['a', 'b', 'c']);
  });

  it('deadline sorts soonest first and pushes undated tasks last', () => {
    const tasks = [
      task({ id: 'none', sortKey: 'a' }),
      task({ id: 'late', deadline: '2026-08-01', sortKey: 'b' }),
      task({ id: 'soon', deadline: '2026-07-04', sortKey: 'c' }),
    ];
    expect(ids(sortTasks(tasks, 'deadline'))).toEqual(['soon', 'late', 'none']);
  });

  it('deadline ties fall through to priority (the next key)', () => {
    const tasks = [
      task({ id: 'low', deadline: '2026-07-04', priority: 'low', sortKey: 'a' }),
      task({ id: 'high', deadline: '2026-07-04', priority: 'high', sortKey: 'b' }),
    ];
    expect(ids(sortTasks(tasks, 'deadline'))).toEqual(['high', 'low']);
  });

  it('priority leads, then deadline decides within the same priority', () => {
    const tasks = [
      task({ id: 'high-late', priority: 'high', deadline: '2026-08-01', sortKey: 'a' }),
      task({ id: 'low-soon', priority: 'low', deadline: '2026-07-04', sortKey: 'b' }),
      task({ id: 'high-soon', priority: 'high', deadline: '2026-07-04', sortKey: 'c' }),
    ];
    expect(ids(sortTasks(tasks, 'priority'))).toEqual(['high-soon', 'high-late', 'low-soon']);
  });

  it('recently added sorts newest first', () => {
    const tasks = [
      task({ id: 'old', createdAt: '2026-01-01T00:00:00Z' }),
      task({ id: 'new', createdAt: '2026-07-01T00:00:00Z' }),
      task({ id: 'mid', createdAt: '2026-04-01T00:00:00Z' }),
    ];
    expect(ids(sortTasks(tasks, 'created'))).toEqual(['new', 'mid', 'old']);
  });

  it('alphabetical sorts titles case-insensitively', () => {
    const tasks = [
      task({ id: 'b', title: 'banana' }),
      task({ id: 'a', title: 'Apple' }),
      task({ id: 'c', title: 'cherry' }),
    ];
    expect(ids(sortTasks(tasks, 'title'))).toEqual(['a', 'b', 'c']);
  });

  it('longest sorts by estimate descending and pushes unestimated tasks last', () => {
    const tasks = [
      task({ id: 'none', sortKey: 'a' }),
      task({ id: 'short', estimatedMinutes: 15, sortKey: 'b' }),
      task({ id: 'long', estimatedMinutes: 120, sortKey: 'c' }),
    ];
    expect(ids(sortTasks(tasks, 'longest'))).toEqual(['long', 'short', 'none']);
  });

  it('shortest sorts by estimate ascending but still keeps unestimated tasks last', () => {
    const tasks = [
      task({ id: 'none', sortKey: 'a' }),
      task({ id: 'long', estimatedMinutes: 120, sortKey: 'b' }),
      task({ id: 'short', estimatedMinutes: 15, sortKey: 'c' }),
    ];
    expect(ids(sortTasks(tasks, 'shortest'))).toEqual(['short', 'long', 'none']);
  });

  it('equal estimates fall through to priority (the lead tiebreaker)', () => {
    const tasks = [
      task({ id: 'low', estimatedMinutes: 30, priority: 'low', sortKey: 'a' }),
      task({ id: 'high', estimatedMinutes: 30, priority: 'high', sortKey: 'b' }),
    ];
    expect(ids(sortTasks(tasks, 'longest'))).toEqual(['high', 'low']);
  });

  it('breaks a full tie with the manual sortKey order', () => {
    // Identical on every field key → only sortKey separates them.
    const common = { priority: 'high' as const, deadline: '2026-07-04', createdAt: '2026-01-01T00:00:00Z', title: 'same' };
    const tasks = [task({ id: 'second', sortKey: 'n', ...common }), task({ id: 'first', sortKey: 'm', ...common })];
    expect(ids(sortTasks(tasks, 'deadline'))).toEqual(['first', 'second']);
  });

  it('does not mutate the input array', () => {
    const tasks = [task({ id: 'b', sortKey: 'b' }), task({ id: 'a', sortKey: 'a' })];
    sortTasks(tasks, 'none');
    expect(ids(tasks)).toEqual(['b', 'a']);
  });
});

describe('isSortMode', () => {
  it('accepts known modes and rejects everything else', () => {
    for (const m of ['none', 'deadline', 'priority', 'created', 'title', 'longest', 'shortest'] as SortMode[]) {
      expect(isSortMode(m)).toBe(true);
    }
    expect(isSortMode('custom')).toBe(false);
    expect(isSortMode('bogus')).toBe(false);
    expect(isSortMode(null)).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';
import type { Task } from './types';
import { subtaskProgressFor } from './subtaskProgress';

const task = (overrides: Partial<Task>): Task => ({
  id: 't1',
  title: 'Plan team offsite',
  status: 'todo',
  description: '- [x] date\n- [ ] venue\n  - [x] book',
  categoryId: 'c1',
  tags: [],
  sortKey: 'a',
  createdAt: '2026-10-01T00:00:00Z',
  ...overrides,
});

describe('subtaskProgressFor', () => {
  it('counts the checklist of an open task', () => {
    expect(subtaskProgressFor(task({}))).toEqual({ done: 2, total: 3 });
  });

  it('hides a checklist with fewer than two items', () => {
    expect(subtaskProgressFor(task({ description: '- [ ] only one' }))).toBeNull();
    expect(subtaskProgressFor(task({ description: 'No checklist' }))).toBeNull();
    expect(subtaskProgressFor(task({ description: undefined }))).toBeNull();
  });

  it('hides on done and archived tasks', () => {
    expect(subtaskProgressFor(task({ status: 'done' }))).toBeNull();
    expect(subtaskProgressFor(task({ status: 'archived' }))).toBeNull();
  });

  it('counts the current occurrence of a recurring task', () => {
    expect(subtaskProgressFor(task({ recurrence: { kind: 'WEEKLY', day: 1 } }))).toEqual({ done: 2, total: 3 });
  });
});

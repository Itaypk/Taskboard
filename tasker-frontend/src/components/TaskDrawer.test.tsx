import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Task } from '../types';
import { TaskDrawer } from './TaskDrawer';

const rent: Task = {
  id: 't1',
  title: 'Pay rent',
  status: 'todo',
  categoryId: 'c1',
  tags: [],
  sortKey: 'm',
  createdAt: '2026-09-01T00:00:00Z',
  relevantFrom: '2026-09-25',
  deadline: '2026-10-02',
  recurrence: { kind: 'MONTHLY', every: null, day: 25, month: null, dueWithinDays: 7 },
};

function renderDrawer(task: Task, onSave = vi.fn()) {
  render(
    <TaskDrawer
      task={task}
      isNew={false}
      open
      categories={[{ id: 'c1', label: 'Home', swatchId: 'mint' }]}
      availableTags={[]}
      defaultCategoryId="c1"
      members={[]}
      currentUserId="u1"
      aiEnabled={false}
      onClose={vi.fn()}
      onSave={onSave}
      onDelete={vi.fn()}
      onMarkDone={vi.fn()}
      onMarkTodo={vi.fn()}
      onSetAssignee={vi.fn()}
    />,
  );
  return onSave;
}

describe('TaskDrawer recurrence', () => {
  afterEach(cleanup);

  // The PUT is a full replace: a drawer save that drops the rule silently stops the task recurring.
  it('sends the existing rule back when saving an unrelated edit', async () => {
    const onSave = renderDrawer(rent);

    fireEvent.change(screen.getByPlaceholderText(i18n.t('taskDrawer.titlePlaceholder')), { target: { value: 'Pay the rent' } });
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.save') }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const saved = onSave.mock.calls[0][0] as Task;
    expect(saved.title).toBe('Pay the rent');
    expect(saved.recurrence).toEqual(rent.recurrence);
    expect(saved.relevantFrom).toBe('2026-09-25');
  });

  it('shows the rule editor instead of the absolute dates while repeating', () => {
    renderDrawer(rent);

    expect(screen.getByLabelText(i18n.t('recurrence.nextOn'))).toHaveValue('2026-09-25');
    expect(screen.queryByText(i18n.t('taskDrawer.deadline'))).not.toBeInTheDocument();
  });

  it('sends a null rule after repeat is switched off', async () => {
    const onSave = renderDrawer(rent);

    fireEvent.click(screen.getByLabelText(i18n.t('recurrence.repeatsToggle')));
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.save') }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect((onSave.mock.calls[0][0] as Task).recurrence).toBeNull();
  });

  it('blocks saving an out-of-range rule', () => {
    const onSave = renderDrawer({ ...rent, recurrence: { kind: 'EVERY_N_DAYS', every: 45, dueWithinDays: null } });

    fireEvent.change(screen.getByLabelText(i18n.t('recurrence.everyDays')), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.save') }));

    expect(screen.getByText(i18n.t('recurrence.invalid'))).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });
});

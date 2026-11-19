import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
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

function renderDrawer(task: Task, onSave = vi.fn(), overrides: Partial<ComponentProps<typeof TaskDrawer>> = {}) {
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
      {...overrides}
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

describe('TaskDrawer auto-save on dismiss', () => {
  afterEach(cleanup);

  const titleInput = () => screen.getByPlaceholderText(i18n.t('taskDrawer.titlePlaceholder'));
  const closeButton = () => screen.getByRole('button', { name: i18n.t('taskDrawer.close') });

  it('saves edits when closed with ×', async () => {
    const onClose = vi.fn();
    const onSave = renderDrawer(rent, vi.fn(), { onClose });

    fireEvent.change(titleInput(), { target: { value: 'Pay the rent' } });
    fireEvent.click(closeButton());

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect((onSave.mock.calls[0][0] as Task).title).toBe('Pay the rent');
  });

  it('just closes, without saving, when nothing changed', () => {
    const onClose = vi.fn();
    const onSave = renderDrawer(rent, vi.fn(), { onClose });

    // Whitespace the save would trim away isn't an edit.
    fireEvent.change(titleInput(), { target: { value: 'Pay rent  ' } });
    fireEvent.click(closeButton());

    expect(onSave).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('discards edits on Escape and Cancel', () => {
    const onClose = vi.fn();
    const onSave = renderDrawer(rent, vi.fn(), { onClose });

    fireEvent.change(titleInput(), { target: { value: 'Pay the rent' } });
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.cancel') }));

    expect(onSave).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('stays open with the reason when the edits are invalid', () => {
    const onClose = vi.fn();
    const onSave = renderDrawer(rent, vi.fn(), { onClose });

    fireEvent.change(titleInput(), { target: { value: '' } });
    fireEvent.click(closeButton());

    expect(screen.getByText(i18n.t('taskDrawer.validation.titleRequired'))).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does not create a new task on dismiss', () => {
    const onClose = vi.fn();
    const onSave = renderDrawer(rent, vi.fn(), { onClose, isNew: true, task: null });

    fireEvent.change(titleInput(), { target: { value: 'Buy milk' } });
    fireEvent.click(closeButton());

    expect(onSave).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('saves edits before marking done, and hands over the saved task', async () => {
    const saved = { ...rent, title: 'Pay the rent' };
    const onMarkDone = vi.fn();
    const onSave = renderDrawer({ ...rent, recurrence: null }, vi.fn().mockResolvedValue(saved), { onMarkDone });

    fireEvent.change(titleInput(), { target: { value: 'Pay the rent' } });
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.markDone') }));

    await waitFor(() => expect(onMarkDone).toHaveBeenCalledWith('t1', saved));
    expect(onSave).toHaveBeenCalledTimes(1);
  });
});

describe('TaskDrawer quick add', () => {
  afterEach(cleanup);

  const plain: Task = { ...rent, recurrence: null };

  it('pins a new task on Enter in the title', async () => {
    const onSave = renderDrawer(rent, vi.fn(), { isNew: true, task: null });

    const title = screen.getByLabelText(i18n.t('taskDrawer.titleLabel'));
    fireEvent.change(title, { target: { value: 'Buy milk' } });
    fireEvent.keyDown(title, { key: 'Enter' });

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect((onSave.mock.calls[0][0] as Task).title).toBe('Buy milk');
  });

  it('keeps the rarely used fields folded until asked, but not the link', () => {
    renderDrawer(plain);

    const toggle = screen.getByRole('button', { name: new RegExp(i18n.t('taskDrawer.moreOptions')) });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByPlaceholderText(i18n.t('taskDrawer.urlPlaceholder'))).toBeInTheDocument();
    expect(screen.queryByLabelText(i18n.t('recurrence.repeatsToggle'))).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByLabelText(i18n.t('recurrence.repeatsToggle'))).toBeInTheDocument();
  });

  it('focuses the title when opened for a new task, not for an existing one', () => {
    const props = { categories: [{ id: 'c1', label: 'Home', swatchId: 'mint' as const }] };
    const { rerender } = render(
      <TaskDrawer task={null} isNew open={false} {...props} availableTags={[]} defaultCategoryId="c1" members={[]}
        currentUserId="u1" aiEnabled={false} onClose={vi.fn()} onSave={vi.fn()} onDelete={vi.fn()} onMarkDone={vi.fn()}
        onMarkTodo={vi.fn()} onSetAssignee={vi.fn()} />,
    );
    expect(document.activeElement).not.toBe(screen.getByPlaceholderText(i18n.t('taskDrawer.titlePlaceholder')));

    rerender(
      <TaskDrawer task={null} isNew open {...props} availableTags={[]} defaultCategoryId="c1" members={[]}
        currentUserId="u1" aiEnabled={false} onClose={vi.fn()} onSave={vi.fn()} onDelete={vi.fn()} onMarkDone={vi.fn()}
        onMarkTodo={vi.fn()} onSetAssignee={vi.fn()} />,
    );
    expect(document.activeElement).toBe(screen.getByLabelText(i18n.t('taskDrawer.titleLabel')));

    cleanup();
    renderDrawer(plain);
    expect(document.activeElement).not.toBe(screen.getByLabelText(i18n.t('taskDrawer.titleLabel')));
  });

  it('adds a suggested board tag in one tap', async () => {
    const onSave = renderDrawer(plain, vi.fn(), { availableTags: [{ id: 'g1', label: 'Kids', colorId: 'violet' }] });

    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.addTagAria', { label: 'Kids' }) }));
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.save') }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect((onSave.mock.calls[0][0] as Task).tags).toEqual([{ id: 'g1', label: 'Kids', colorId: 'violet' }]);
  });

  it('saves the picked priority and time estimate', async () => {
    const onSave = renderDrawer(plain);

    fireEvent.click(screen.getByRole('radio', { name: i18n.t('taskDrawer.priorityHigh') }));
    fireEvent.click(screen.getByRole('button', { name: '1h' }));
    fireEvent.click(screen.getByRole('button', { name: i18n.t('taskDrawer.save') }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const saved = onSave.mock.calls[0][0] as Task;
    expect(saved.priority).toBe('high');
    expect(saved.estimatedMinutes).toBe(60);
  });

  it('shows a custom estimate in the minutes field', () => {
    renderDrawer({ ...plain, estimatedMinutes: 90 });

    expect(screen.getByRole('button', { name: i18n.t('taskDrawer.timeCustom') })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText(i18n.t('taskDrawer.timeMinutesPlaceholder'))).toHaveValue(90);
  });
});

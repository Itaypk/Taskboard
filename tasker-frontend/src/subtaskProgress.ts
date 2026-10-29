import type { Task } from './types';
import { checklistProgress, type ChecklistProgress } from './checklist';

/** Below this many checklist items the boxes on the card already say it all. */
const MIN_ITEMS = 2;

/**
 * The checklist progress a card should show for a task, or null when it shows none: done and archived
 * tasks (their own state is what matters) and short or absent checklists. A recurring task's
 * checklist is unticked by the server each time it rolls forward, so its count is per occurrence.
 * Counts the full description, not the card's truncated preview.
 */
export function subtaskProgressFor(task: Task): ChecklistProgress | null {
  if (task.status !== 'todo' || !task.description) return null;
  const progress = checklistProgress(task.description);
  return progress.total >= MIN_ITEMS ? progress : null;
}

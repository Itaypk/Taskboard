import { useTranslation } from 'react-i18next';
import type { CurrentPlan } from '../types';
import { formatDate } from '../i18n/format';
import { formatRelative, formatTimeSlot } from '../utils';
import styles from './PlanDetails.module.css';

interface PlanDetailsProps {
  plan: CurrentPlan;
  onTaskClick: (taskId: string) => void;
  onTaskContextMenu?: (e: React.MouseEvent, taskId: string) => void;
}

/**
 * Read-only rendering of a finalized/active plan: status, scheduled tasks, then the assistant's notes
 * on the week (only when there are any — the schedule itself is the plan's record).
 */
export function PlanDetails({ plan, onTaskClick, onTaskContextMenu }: PlanDetailsProps) {
  const { t } = useTranslation();
  const weekNote = plan.weekNote?.trim() || null;
  const hasNotes = weekNote !== null || plan.revisionNotes.length > 0;
  return (
    <>
      <div className={styles.metaRow}>
        <span className={`${styles.statusPill} ${plan.status === 'active' ? styles.statusActive : styles.statusCompleted}`}>
          {plan.status === 'active' ? t('planDetails.inProgress') : t('planDetails.finalized')}
        </span>
        <span className={styles.timestamp}>
          {plan.endedAt
            ? t('planDetails.timestampRange', { started: formatRelative(plan.startedAt), ended: formatRelative(plan.endedAt) })
            : t('planDetails.timestampStarted', { started: formatRelative(plan.startedAt) })}
        </span>
      </div>

      <section className={styles.section}>
        <h4 className={styles.sectionLabel}>{t('planDetails.tasksHeading', { count: plan.tasks.length })}</h4>
        {plan.tasks.length === 0 ? (
          <p className={styles.summaryMuted}>{t('planDetails.noTasks')}</p>
        ) : (
          <ul className={styles.taskList}>
            {plan.tasks.map(task => {
              const isArchived = task.status === 'archived';
              // A completed recurring task stays `todo` (it rolled to its next date); its completion
              // during this plan's week is what marks it done here.
              const completedThisWeek = task.recurrence != null && !!task.lastCompletedOn && task.lastCompletedOn >= plan.weekStart;
              const isDone = task.status === 'done' || isArchived || completedThisWeek;
              return (
                <li key={task.id}>
                  <button
                    type="button"
                    className={styles.taskItem}
                    disabled={isArchived}
                    onClick={isArchived ? undefined : () => onTaskClick(task.id)}
                    onContextMenu={!isArchived && onTaskContextMenu ? e => { e.preventDefault(); onTaskContextMenu(e, task.id); } : undefined}
                  >
                    <span
                      className={`${styles.statusDot} ${isDone ? styles.statusDotDone : styles.statusDotTodo}`}
                      aria-label={isDone ? t('planDetails.done') : t('planDetails.todo')}
                    />
                    <span className={styles.taskContent}>
                      <span className={`${styles.taskTitle} ${isDone ? styles.taskTitleDone : ''}`}>
                        {task.title}
                      </span>
                      {task.slots.length > 0 && (
                        <span className={styles.taskSlot}>
                          {formatTimeSlot(task.slots[0].startIso, task.slots[0].endIso)}
                        </span>
                      )}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>
      {hasNotes && (
        <section className={styles.section}>
          <h4 className={styles.sectionLabel}>{t('planDetails.notesHeading')}</h4>
          {weekNote && <p className={styles.summary}>{weekNote}</p>}
          {plan.revisionNotes.length > 0 && (
            <ul className={styles.revisionNotes}>
              {plan.revisionNotes.map(note => (
                <li key={note.at} className={styles.revisionNote}>
                  <span className={styles.revisionDate}>
                    {t('planDetails.revisedOn', { date: formatDate(new Date(note.at), { month: 'short', day: 'numeric' }) })}
                  </span>
                  <span className={styles.revisionText}>{note.text}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </>
  );
}

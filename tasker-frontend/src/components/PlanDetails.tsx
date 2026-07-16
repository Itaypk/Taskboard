import { useTranslation } from 'react-i18next';
import type { CurrentPlan } from '../types';
import { formatRelative, formatTimeSlot } from '../utils';
import styles from './PlanDetails.module.css';

interface PlanDetailsProps {
  plan: CurrentPlan;
  onTaskClick: (taskId: string) => void;
  onTaskContextMenu?: (e: React.MouseEvent, taskId: string) => void;
}

/** Read-only rendering of a finalized/active plan: status, summary, and scheduled tasks. */
export function PlanDetails({ plan, onTaskClick, onTaskContextMenu }: PlanDetailsProps) {
  const { t } = useTranslation();
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
        <h4 className={styles.sectionLabel}>{t('planDetails.summaryHeading')}</h4>
        {plan.summary && plan.summary.trim().length > 0 ? (
          <p className={styles.summary}>{plan.summary}</p>
        ) : (
          <p className={styles.summaryMuted}>{t('planDetails.noSummary')}</p>
        )}
      </section>

      <section className={styles.section}>
        <h4 className={styles.sectionLabel}>{t('planDetails.tasksHeading', { count: plan.tasks.length })}</h4>
        {plan.tasks.length === 0 ? (
          <p className={styles.summaryMuted}>{t('planDetails.noTasks')}</p>
        ) : (
          <ul className={styles.taskList}>
            {plan.tasks.map(task => {
              const isArchived = task.status === 'archived';
              const isDone = task.status === 'done' || isArchived;
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
    </>
  );
}

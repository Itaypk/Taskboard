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
  return (
    <>
      <div className={styles.metaRow}>
        <span className={`${styles.statusPill} ${plan.status === 'active' ? styles.statusActive : styles.statusCompleted}`}>
          {plan.status === 'active' ? 'In progress' : 'Last finalized'}
        </span>
        <span className={styles.timestamp}>
          Started {formatRelative(plan.startedAt)}
          {plan.endedAt ? ` · finished ${formatRelative(plan.endedAt)}` : ''}
        </span>
      </div>

      <section className={styles.section}>
        <h4 className={styles.sectionLabel}>Summary</h4>
        {plan.summary && plan.summary.trim().length > 0 ? (
          <p className={styles.summary}>{plan.summary}</p>
        ) : (
          <p className={styles.summaryMuted}>No summary recorded for this session.</p>
        )}
      </section>

      <section className={styles.section}>
        <h4 className={styles.sectionLabel}>Tasks ({plan.tasks.length})</h4>
        {plan.tasks.length === 0 ? (
          <p className={styles.summaryMuted}>No tasks were scheduled in this plan.</p>
        ) : (
          <ul className={styles.taskList}>
            {plan.tasks.map(task => (
              <li key={task.id}>
                <button
                  type="button"
                  className={styles.taskItem}
                  onClick={() => onTaskClick(task.id)}
                  onContextMenu={onTaskContextMenu ? e => { e.preventDefault(); onTaskContextMenu(e, task.id); } : undefined}
                >
                  <span
                    className={`${styles.statusDot} ${task.status === 'done' ? styles.statusDotDone : styles.statusDotTodo}`}
                    aria-label={task.status === 'done' ? 'Done' : 'To do'}
                  />
                  <span className={styles.taskContent}>
                    <span className={`${styles.taskTitle} ${task.status === 'done' ? styles.taskTitleDone : ''}`}>
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
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

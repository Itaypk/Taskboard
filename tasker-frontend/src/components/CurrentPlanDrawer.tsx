import { useEffect } from 'react';
import type { CurrentPlan } from '../types';
import styles from './CurrentPlanDrawer.module.css';

interface CurrentPlanDrawerProps {
  plan: CurrentPlan | null;
  open: boolean;
  onClose: () => void;
  onTaskClick: (taskId: string) => void;
}

export function CurrentPlanDrawer({ plan, open, onClose, onTaskClick }: CurrentPlanDrawerProps) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  return (
    <>
      <div
        className={`overlay${open ? ' overlay--open' : ''}`}
        onClick={onClose}
      />
      <aside
        className={`drawer${open ? ' drawer--open' : ''}`}
        aria-hidden={!open}
        role="dialog"
        aria-modal="true"
        aria-label="This week's plan"
      >
        <div className="drawer__header">
          <span className="drawer__label">This week's plan</span>
          <button className="drawer__close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="drawer__body">
          {plan === null ? (
            <div className={styles.empty}>
              <p className={styles.emptyTitle}>No plan yet.</p>
              <p className={styles.emptyHint}>Start a weekly planning session on Telegram and the agreed plan will show up here.</p>
            </div>
          ) : (
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

              {plan.summary && plan.summary.trim().length > 0 ? (
                <section className={styles.section}>
                  <h4 className={styles.sectionLabel}>Summary</h4>
                  <p className={styles.summary}>{plan.summary}</p>
                </section>
              ) : (
                <section className={styles.section}>
                  <h4 className={styles.sectionLabel}>Summary</h4>
                  <p className={styles.summaryMuted}>No summary recorded for this session.</p>
                </section>
              )}

              <section className={styles.section}>
                <h4 className={styles.sectionLabel}>
                  Tasks ({plan.tasks.length})
                </h4>
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
                        >
                          <span
                            className={`${styles.statusDot} ${task.status === 'done' ? styles.statusDotDone : styles.statusDotTodo}`}
                            aria-label={task.status === 'done' ? 'Done' : 'To do'}
                          />
                          <span className={`${styles.taskTitle} ${task.status === 'done' ? styles.taskTitleDone : ''}`}>
                            {task.title}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </div>
      </aside>
    </>
  );
}

function formatRelative(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const diffMs = Date.now() - date.getTime();
  const diffHours = Math.round(diffMs / (1000 * 60 * 60));
  if (diffHours < 1) return 'just now';
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.round(diffHours / 24);
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

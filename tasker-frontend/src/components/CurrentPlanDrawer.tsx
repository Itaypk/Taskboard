import { useEffect } from 'react';
import type { CurrentPlan } from '../types';
import { formatRelative, formatTimeSlot } from '../utils';
import styles from './CurrentPlanDrawer.module.css';

interface CurrentPlanDrawerProps {
  plan: CurrentPlan | null;
  open: boolean;
  onClose: () => void;
  onTaskClick: (taskId: string) => void;
  onTaskContextMenu?: (e: React.MouseEvent, taskId: string) => void;
}

export function CurrentPlanDrawer({ plan, open, onClose, onTaskClick, onTaskContextMenu }: CurrentPlanDrawerProps) {
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
          <div className={styles.titleGroup}>
            <span className="drawer__label">This week's plan</span>
            {plan && <span className={styles.weekRange}>{formatWeekRange(plan.weekStart, plan.weekEnd)}</span>}
          </div>
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
          )}
        </div>
      </aside>
    </>
  );
}

function formatWeekRange(weekStart: string, weekEnd: string): string {
  const start = new Date(`${weekStart}T00:00:00`);
  const end = new Date(`${weekEnd}T00:00:00`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return '';
  const year = start.getFullYear();
  const startMonth = start.toLocaleDateString(undefined, { month: 'short' });
  const endMonth = end.toLocaleDateString(undefined, { month: 'short' });
  const startDay = start.getDate();
  const endDay = end.getDate();
  if (startMonth === endMonth) {
    return `${startMonth} ${startDay}–${endDay}, ${year}`;
  }
  return `${startMonth} ${startDay} – ${endMonth} ${endDay}, ${year}`;
}


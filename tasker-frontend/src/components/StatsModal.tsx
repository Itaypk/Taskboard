import { useEffect, useState } from 'react';
import styles from './StatsModal.module.css';
import { fetchStats } from '../api';
import type { Stats } from '../types';

interface StatsModalProps {
  open: boolean;
  onClose: () => void;
}

/** Rounds to at most one decimal and drops a trailing ".0" (3.0 → "3", 2.5 → "2.5"). */
function formatAvg(n: number): string {
  return String(Number(n.toFixed(1)));
}

function formatJoined(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function formatCompletion(seconds: number | null): string {
  if (seconds == null) return 'Not enough completed tasks yet';
  const hours = seconds / 3600;
  return hours >= 24 ? `${formatAvg(hours / 24)} days` : `${formatAvg(hours)} hours`;
}

function isEmpty(s: Stats): boolean {
  return s.openTasks === 0 && s.completedTasks === 0 && s.planningSessions === 0 && s.avgTasksCreatedPerWeek === 0;
}

export function StatsModal({ open, onClose }: StatsModalProps) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  useEffect(() => {
    if (!open) return;
    let active = true;
    const load = async () => {
      setLoading(true);
      setError(false);
      try {
        const s = await fetchStats();
        if (active) setStats(s);
      } catch {
        if (active) setError(true);
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
  }, [open]);

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label="Your stats">
        <div className="modal__header">
          <span className="modal__title">Your stats</span>
          <button className="drawer__close" onClick={onClose} aria-label="Close stats">×</button>
        </div>
        <div className="modal__body">
          {loading && <p className={styles.muted}>Loading…</p>}
          {!loading && error && <p className={styles.muted}>Couldn't load your stats. Please try again.</p>}
          {!loading && !error && stats && (isEmpty(stats)
            ? <p className={styles.muted}>No stats yet — add a few tasks and plan your week, then check back here.</p>
            : (
              <div className={styles.stats}>
                <div className={styles.highlights}>
                  <div className={styles.highlight}>
                    <span className={styles.highlightValue}>{stats.openTasks}</span>
                    <span className={styles.highlightLabel}>Open tasks</span>
                  </div>
                  <div className={styles.highlight}>
                    <span className={styles.highlightValue}>{stats.completedTasks}</span>
                    <span className={styles.highlightLabel}>Completed</span>
                  </div>
                </div>
                <dl className={styles.rows}>
                  {stats.joinedAt && (
                    <div className={styles.row}>
                      <dt className={styles.rowLabel}>Joined</dt>
                      <dd className={styles.rowValue}>{formatJoined(stats.joinedAt)}</dd>
                    </div>
                  )}
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>Tasks created (all time)</dt>
                    <dd className={styles.rowValue}>{stats.totalTasksCreated}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>Tasks completed (all time)</dt>
                    <dd className={styles.rowValue}>{stats.totalTasksCompleted}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>New tasks / week</dt>
                    <dd className={styles.rowValue}>{formatAvg(stats.avgTasksCreatedPerWeek)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>Completed / week</dt>
                    <dd className={styles.rowValue}>{formatAvg(stats.avgTasksCompletedPerWeek)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>Avg. time to complete</dt>
                    <dd className={styles.rowValue}>{formatCompletion(stats.avgCompletionSeconds)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>Planning sessions</dt>
                    <dd className={styles.rowValue}>{stats.planningSessions}</dd>
                  </div>
                </dl>
              </div>
            ))}
        </div>
      </div>
    </div>
  );
}

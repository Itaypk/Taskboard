import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './StatsModal.module.css';
import { fetchStats } from '../api';
import { formatDate } from '../i18n/format';
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
  return formatDate(iso, { year: 'numeric', month: 'short', day: 'numeric' });
}

function isEmpty(s: Stats): boolean {
  return s.openTasks === 0 && s.completedTasks === 0 && s.planningSessions === 0 && s.avgTasksCreatedPerWeek === 0;
}

function formatCompletion(seconds: number | null, t: (key: string, opts?: Record<string, unknown>) => string): string {
  if (seconds == null) return t('statsModal.notEnoughData');
  const hours = seconds / 3600;
  return hours >= 24
    ? t('statsModal.days', { count: Number(formatAvg(hours / 24)) })
    : t('statsModal.hours', { count: Number(formatAvg(hours)) });
}

export function StatsModal({ open, onClose }: StatsModalProps) {
  const { t } = useTranslation();
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
      <div className="modal" role="dialog" aria-modal="true" aria-label={t('statsModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{t('statsModal.title')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('statsModal.close')}>×</button>
        </div>
        <div className="modal__body">
          {loading && <p className={styles.muted}>{t('statsModal.loading')}</p>}
          {!loading && error && <p className={styles.muted}>{t('statsModal.loadError')}</p>}
          {!loading && !error && stats && (isEmpty(stats)
            ? <p className={styles.muted}>{t('statsModal.empty')}</p>
            : (
              <div className={styles.stats}>
                <div className={styles.highlights}>
                  <div className={styles.highlight}>
                    <span className={styles.highlightValue}>{stats.openTasks}</span>
                    <span className={styles.highlightLabel}>{t('statsModal.openTasks')}</span>
                  </div>
                  <div className={styles.highlight}>
                    <span className={styles.highlightValue}>{stats.completedTasks}</span>
                    <span className={styles.highlightLabel}>{t('statsModal.completed')}</span>
                  </div>
                </div>
                <dl className={styles.rows}>
                  {stats.joinedAt && (
                    <div className={styles.row}>
                      <dt className={styles.rowLabel}>{t('statsModal.joined')}</dt>
                      <dd className={styles.rowValue}>{formatJoined(stats.joinedAt)}</dd>
                    </div>
                  )}
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.totalCreated')}</dt>
                    <dd className={styles.rowValue}>{stats.totalTasksCreated}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.totalCompleted')}</dt>
                    <dd className={styles.rowValue}>{stats.totalTasksCompleted}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.newPerWeek')}</dt>
                    <dd className={styles.rowValue}>{formatAvg(stats.avgTasksCreatedPerWeek)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.completedPerWeek')}</dt>
                    <dd className={styles.rowValue}>{formatAvg(stats.avgTasksCompletedPerWeek)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.avgCompletionTime')}</dt>
                    <dd className={styles.rowValue}>{formatCompletion(stats.avgCompletionSeconds, t)}</dd>
                  </div>
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{t('statsModal.planningSessions')}</dt>
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

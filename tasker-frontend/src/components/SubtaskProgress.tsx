import { useTranslation } from 'react-i18next';
import type { ChecklistProgress } from '../checklist';
import styles from './SubtaskProgress.module.css';

interface SubtaskProgressProps {
  progress: ChecklistProgress;
}

/**
 * A highlighter swipe over a faint pencil guide line, reaching as far along as the checklist is done.
 * `row` sits behind the card's category row (whose parent must be positioned); `underline` sits in
 * flow, under a list row's title. Decorative: the count beside it carries the accessible text.
 */
export function SubtaskStroke({ progress, variant }: SubtaskProgressProps & { variant: 'row' | 'underline' }) {
  const { done, total } = progress;
  const className = [styles.stroke, styles[variant], done === total ? styles.full : ''].filter(Boolean).join(' ');
  return (
    <span className={className} aria-hidden="true">
      {done > 0 && <span className={styles.swipe} style={{ width: `calc(${(done / total) * 100}% + 3px)` }} />}
    </span>
  );
}

/** "☑ 2/4", with a full sentence for screen readers and on hover. */
export function SubtaskCount({ progress }: SubtaskProgressProps) {
  const { t } = useTranslation();
  const { done, total } = progress;
  const label = t('taskCard.subtaskProgress', { done, total });
  return (
    <span className={`${styles.count} ${done === total ? styles.full : ''}`} title={label}>
      <CheckIcon />
      <span aria-hidden="true" dir="ltr">{done}/{total}</span>
      <span className={styles.srOnly}>{label}</span>
    </span>
  );
}

function CheckIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden>
      <rect x="1.5" y="1.5" width="9" height="9" rx="1.5" />
      <path d="M3.8 6.2l1.6 1.6 3-3.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

import { useTranslation } from 'react-i18next';
import type { TaskFilter } from '../types';
import styles from './BoardFilter.module.css';

interface BoardFilterProps {
  value: TaskFilter;
  onChange: (next: TaskFilter) => void;
  hasCurrentPlan: boolean;
}

const OPTIONS: { id: TaskFilter; labelKey: string; shortLabelKey?: string; planOnly?: boolean }[] = [
  { id: 'todo', labelKey: 'boardFilter.todo' },
  { id: 'plan', labelKey: 'boardFilter.thisWeeksPlan', shortLabelKey: 'boardFilter.week', planOnly: true },
  { id: 'done', labelKey: 'boardFilter.done' },
  { id: 'all',  labelKey: 'boardFilter.all' },
];

export function BoardFilter({ value, onChange, hasCurrentPlan }: BoardFilterProps) {
  const { t } = useTranslation();
  return (
    <div className={styles.row} role="radiogroup" aria-label={t('boardFilter.filterTasks')}>
      {OPTIONS.map(opt => {
        const disabled = opt.planOnly === true && !hasCurrentPlan;
        const selected = value === opt.id;
        return (
          <button
            key={opt.id}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            title={disabled ? t('boardFilter.noPlanYet') : undefined}
            className={`${styles.chip} ${selected ? styles.chipSelected : ''}`}
            onClick={() => onChange(opt.id)}
          >
            {opt.shortLabelKey ? (
              <>
                <span className={styles.labelFull}>{t(opt.labelKey)}</span>
                <span className={styles.labelShort}>{t(opt.shortLabelKey)}</span>
              </>
            ) : t(opt.labelKey)}
          </button>
        );
      })}
    </div>
  );
}

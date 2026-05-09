import type { TaskFilter } from '../types';
import styles from './BoardFilter.module.css';

interface BoardFilterProps {
  value: TaskFilter;
  onChange: (next: TaskFilter) => void;
  hasCurrentPlan: boolean;
}

const OPTIONS: { id: TaskFilter; label: string; planOnly?: boolean }[] = [
  { id: 'todo', label: 'To do' },
  { id: 'plan', label: "This week's plan", planOnly: true },
  { id: 'done', label: 'Done' },
  { id: 'all',  label: 'All' },
];

export function BoardFilter({ value, onChange, hasCurrentPlan }: BoardFilterProps) {
  return (
    <div className={styles.row} role="radiogroup" aria-label="Filter tasks">
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
            title={disabled ? 'No active or recent plan yet' : undefined}
            className={`${styles.chip} ${selected ? styles.chipSelected : ''}`}
            onClick={() => onChange(opt.id)}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

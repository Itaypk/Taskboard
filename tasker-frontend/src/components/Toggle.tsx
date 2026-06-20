import { useId, type ReactNode } from 'react';
import styles from './Toggle.module.css';

interface ToggleProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  /** Visible label rendered next to the switch. Clicking it flips the toggle. */
  label: ReactNode;
  disabled?: boolean;
  /** Optional id forwarded to the underlying input; auto-generated otherwise. */
  id?: string;
}

/**
 * Pill-style on/off switch. Wraps a real <input type="checkbox"> so it works inside forms
 * and keeps native focus / keyboard semantics; only the visual rail + knob are custom.
 */
export function Toggle({ checked, onChange, label, disabled = false, id }: ToggleProps) {
  const fallback = useId();
  const inputId = id ?? fallback;
  return (
    <label
      className={`${styles.root} ${disabled ? styles.disabled : ''}`}
      htmlFor={inputId}
    >
      <input
        id={inputId}
        type="checkbox"
        className={styles.input}
        checked={checked}
        disabled={disabled}
        onChange={e => onChange(e.target.checked)}
      />
      <span className={styles.track} aria-hidden="true">
        <span className={styles.knob} />
      </span>
      <span className={styles.label}>{label}</span>
    </label>
  );
}

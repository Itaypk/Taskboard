import { useState, useEffect } from 'react';
import styles from './ScheduleTaskModal.module.css';

interface ScheduleTaskModalProps {
  open: boolean;
  taskTitle: string;
  weekStart: string; // YYYY-MM-DD
  weekEnd: string;   // YYYY-MM-DD
  onConfirm: (startIso: string, endIso: string) => void;
  onClose: () => void;
}

function dateRange(weekStart: string, weekEnd: string): Date[] {
  const dates: Date[] = [];
  const start = new Date(weekStart + 'T00:00:00');
  const end = new Date(weekEnd + 'T00:00:00');
  for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    dates.push(new Date(d));
  }
  return dates;
}

function toLocalIso(dateStr: string, timeStr: string): string {
  return new Date(`${dateStr}T${timeStr}`).toISOString();
}

function formatDayLabel(date: Date): { weekday: string; date: string } {
  return {
    weekday: date.toLocaleDateString(undefined, { weekday: 'short' }),
    date: date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
  };
}

function toDateStr(d: Date): string {
  return d.toLocaleDateString('en-CA'); // YYYY-MM-DD in local time
}

function defaultDay(days: Date[]): Date {
  const today = toDateStr(new Date());
  return days.find(d => toDateStr(d) >= today) ?? days[0];
}

export function ScheduleTaskModal({
  open, taskTitle, weekStart, weekEnd, onConfirm, onClose,
}: ScheduleTaskModalProps) {
  const days = dateRange(weekStart, weekEnd);
  const [selectedDay, setSelectedDay] = useState<Date>(() => defaultDay(days));
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('10:00');

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const endTimeError = endTime <= startTime;

  const handleConfirm = () => {
    if (endTimeError) return;
    const dateStr = toDateStr(selectedDay);
    onConfirm(toLocalIso(dateStr, startTime), toLocalIso(dateStr, endTime));
  };

  return (
    <div
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label="Schedule task"
        style={{ width: 440 }}
      >
        <div className="modal__header">
          <span className="modal__title">Schedule task</span>
        </div>

        <div className="modal__body">
          <p className={styles.subtitle}>
            Adding <strong>{taskTitle}</strong> to this week's plan.
          </p>

          <div className="field">
            <label className="field__label">Day</label>
            <div className={styles.dayGrid}>
              {days.map(day => {
                const { weekday, date } = formatDayLabel(day);
                const isSelected = toDateStr(day) === toDateStr(selectedDay);
                return (
                  <button
                    key={toDateStr(day)}
                    type="button"
                    className={`${styles.dayBtn} ${isSelected ? styles.dayBtnSelected : ''}`}
                    onClick={() => setSelectedDay(day)}
                  >
                    <span className={styles.dayWeekday}>{weekday}</span>
                    <span className={styles.dayDate}>{date}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className={styles.timeRow}>
            <div className="field">
              <label className="field__label">Start time</label>
              <input
                className="field__input"
                type="time"
                value={startTime}
                onChange={e => setStartTime(e.target.value)}
              />
            </div>
            <div className={styles.timeSep}>→</div>
            <div className="field">
              <label className="field__label">End time</label>
              <input
                className={`field__input${endTimeError ? ' field__input--error' : ''}`}
                type="time"
                value={endTime}
                onChange={e => setEndTime(e.target.value)}
              />
            </div>
          </div>
          {endTimeError && (
            <p className={styles.timeError}>End time must be after start time.</p>
          )}
        </div>

        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={handleConfirm}
            disabled={endTimeError}
          >
            Add to plan
          </button>
        </div>
      </div>
    </div>
  );
}

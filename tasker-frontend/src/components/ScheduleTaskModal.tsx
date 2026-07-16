import { useState, useEffect } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { formatDate } from '../i18n/format';
import styles from './ScheduleTaskModal.module.css';

interface ScheduleTaskModalProps {
  open: boolean;
  taskTitle: string;
  weekStart: string; // YYYY-MM-DD
  weekEnd: string;   // YYYY-MM-DD
  /** Estimated task length in minutes; drives the start/end auto-link (defaults to 30 when unset). */
  estimatedMinutes?: number;
  /** When set, the dialog opens in "reschedule" mode, prefilled from this existing slot. */
  initialSlot?: { startIso: string; endIso: string };
  onConfirm: (startIso: string, endIso: string) => void;
  onClose: () => void;
}

const DEFAULT_DURATION_MIN = 30;

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
    weekday: formatDate(date, { weekday: 'short' }),
    date: formatDate(date, { month: 'short', day: 'numeric' }),
  };
}

/** Local-time YYYY-MM-DD (formatted explicitly rather than via a locale that happens to match). */
function toDateStr(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

/** Local wall-clock "HH:MM" of an ISO instant. */
function toTimeStr(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function defaultDay(days: Date[]): Date {
  const today = toDateStr(new Date());
  return days.find(d => toDateStr(d) >= today) ?? days[0];
}

/** Shifts a "HH:MM" time by [deltaMin], returning null if it would cross a day boundary. */
function shiftTime(time: string, deltaMin: number): string | null {
  const [h, m] = time.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return null;
  const total = h * 60 + m + deltaMin;
  if (total < 0 || total >= 24 * 60) return null;
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

export function ScheduleTaskModal({
  open, taskTitle, weekStart, weekEnd, estimatedMinutes, initialSlot, onConfirm, onClose,
}: ScheduleTaskModalProps) {
  const { t } = useTranslation();
  const days = dateRange(weekStart, weekEnd);
  const reschedule = initialSlot != null;
  const duration = estimatedMinutes && estimatedMinutes > 0 ? estimatedMinutes : DEFAULT_DURATION_MIN;

  const [selectedDay, setSelectedDay] = useState<Date>(() => {
    if (initialSlot) {
      const slotDay = toDateStr(new Date(initialSlot.startIso));
      return days.find(d => toDateStr(d) === slotDay) ?? defaultDay(days);
    }
    return defaultDay(days);
  });
  const [startTime, setStartTime] = useState(() => initialSlot ? toTimeStr(new Date(initialSlot.startIso)) : '09:00');
  const [endTime, setEndTime] = useState(() => initialSlot ? toTimeStr(new Date(initialSlot.endIso)) : '10:00');
  // Once a field is edited by hand it's never auto-overwritten; the other side may still auto-follow.
  const [startTouched, setStartTouched] = useState(false);
  const [endTouched, setEndTouched] = useState(false);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const handleStartChange = (value: string) => {
    setStartTime(value);
    setStartTouched(true);
    if (!endTouched) {
      const linked = shiftTime(value, duration);
      if (linked) setEndTime(linked);
    }
  };

  const handleEndChange = (value: string) => {
    setEndTime(value);
    setEndTouched(true);
    if (!startTouched) {
      const linked = shiftTime(value, -duration);
      if (linked) setStartTime(linked);
    }
  };

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
        aria-label={reschedule ? t('scheduleTaskModal.rescheduleTitle') : t('scheduleTaskModal.scheduleTitle')}
        style={{ width: 440 }}
      >
        <div className="modal__header">
          <span className="modal__title">
            {reschedule ? t('scheduleTaskModal.rescheduleTitle') : t('scheduleTaskModal.scheduleTitle')}
          </span>
        </div>

        <div className="modal__body">
          <p className={styles.subtitle}>
            {reschedule ? (
              <Trans i18nKey="scheduleTaskModal.subtitleReschedule" values={{ taskTitle }} components={{ strong: <strong /> }} />
            ) : (
              <Trans i18nKey="scheduleTaskModal.subtitleSchedule" values={{ taskTitle }} components={{ strong: <strong /> }} />
            )}
          </p>

          <div className="field">
            <label className="field__label">{t('scheduleTaskModal.day')}</label>
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
              <label className="field__label">{t('scheduleTaskModal.startTime')}</label>
              <input
                className="field__input"
                type="time"
                value={startTime}
                onChange={e => handleStartChange(e.target.value)}
              />
            </div>
            <div className={styles.timeSep}>→</div>
            <div className="field">
              <label className="field__label">{t('scheduleTaskModal.endTime')}</label>
              <input
                className={`field__input${endTimeError ? ' field__input--error' : ''}`}
                type="time"
                value={endTime}
                onChange={e => handleEndChange(e.target.value)}
              />
            </div>
          </div>
          {endTimeError && (
            <p className={styles.timeError}>{t('scheduleTaskModal.endTimeError')}</p>
          )}
        </div>

        <div className="modal__footer">
          <button type="button" className="btn btn--ghost" onClick={onClose}>{t('scheduleTaskModal.cancel')}</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={handleConfirm}
            disabled={endTimeError}
          >
            {reschedule ? t('scheduleTaskModal.updateSlot') : t('scheduleTaskModal.addToPlan')}
          </button>
        </div>
      </div>
    </div>
  );
}

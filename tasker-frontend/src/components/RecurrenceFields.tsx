import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { Recurrence, RecurrenceKind } from '../types';
import {
  RECURRENCE_KINDS, daysInMonthOfYear, defaultRule, firstOccurrence, isRuleValid, monthName,
  recurrenceSummary, todayIso, weekdayName,
} from '../recurrence';
import styles from './RecurrenceFields.module.css';

const KIND_LABEL_KEYS: Record<RecurrenceKind, string> = {
  EVERY_N_DAYS: 'recurrence.kindEveryNDays',
  EVERY_N_MONTHS: 'recurrence.kindEveryNMonths',
  WEEKLY: 'recurrence.kindWeekly',
  MONTHLY: 'recurrence.kindMonthly',
  YEARLY: 'recurrence.kindYearly',
};

const range = (n: number) => Array.from({ length: n }, (_, i) => i + 1);
const numberOrNull = (raw: string) => (raw === '' ? null : Number(raw));

interface RecurrenceFieldsProps {
  recurrence: Recurrence;
  /** The next occurrence — the task's `relevantFrom`. */
  relevantFrom: string;
  error?: string;
  onChange: (next: { recurrence: Recurrence; relevantFrom: string }) => void;
}

/**
 * The task drawer's rule editor, shown in place of Deadline / Available-from while "Repeat" is on.
 * The deadline isn't editable here: the server derives it from "Next on" + "Due within".
 */
export function RecurrenceFields({ recurrence, relevantFrom, error, onChange }: RecurrenceFieldsProps) {
  const { t } = useTranslation();
  const id = useId();
  const today = todayIso();

  // Calendar rules re-suggest "Next on" when their day changes, so "yearly on 03-14" set in September
  // doesn't come due today. Interval rules keep whatever date is there. Either way the user can edit it.
  const setRule = (rule: Recurrence, resuggest: boolean) =>
    onChange({ recurrence: rule, relevantFrom: resuggest || !relevantFrom ? firstOccurrence(rule, today) : relevantFrom });

  const primaryField = () => {
    switch (recurrence.kind) {
      case 'EVERY_N_DAYS':
      case 'EVERY_N_MONTHS':
        return (
          <>
            <label className="field__label" htmlFor={`${id}-every`}>
              {t(recurrence.kind === 'EVERY_N_DAYS' ? 'recurrence.everyDays' : 'recurrence.everyMonths')}
            </label>
            <input
              id={`${id}-every`}
              className="field__input"
              type="number"
              min={1}
              value={recurrence.every ?? ''}
              onChange={e => setRule({ ...recurrence, every: numberOrNull(e.target.value) }, false)}
            />
          </>
        );
      case 'WEEKLY':
        return (
          <>
            <label className="field__label" htmlFor={`${id}-weekday`}>{t('recurrence.weekday')}</label>
            <select
              id={`${id}-weekday`}
              className="field__select"
              value={recurrence.day ?? 1}
              onChange={e => setRule({ ...recurrence, day: Number(e.target.value) }, true)}
            >
              {range(7).map(d => <option key={d} value={d}>{weekdayName(d)}</option>)}
            </select>
          </>
        );
      case 'MONTHLY':
        return (
          <>
            <label className="field__label" htmlFor={`${id}-day`}>{t('recurrence.dayOfMonth')}</label>
            <select
              id={`${id}-day`}
              className="field__select"
              value={recurrence.day ?? 1}
              onChange={e => setRule({ ...recurrence, day: Number(e.target.value) }, true)}
            >
              {range(31).map(d => <option key={d} value={d}>{d === 31 ? t('recurrence.lastDay') : d}</option>)}
            </select>
          </>
        );
      case 'YEARLY':
        return (
          <>
            <label className="field__label" htmlFor={`${id}-month`}>{t('recurrence.month')}</label>
            <select
              id={`${id}-month`}
              className="field__select"
              value={recurrence.month ?? 1}
              onChange={e => {
                const month = Number(e.target.value);
                setRule({ ...recurrence, month, day: Math.min(recurrence.day ?? 1, daysInMonthOfYear(month)) }, true);
              }}
            >
              {range(12).map(m => <option key={m} value={m}>{monthName(m)}</option>)}
            </select>
          </>
        );
    }
  };

  return (
    <>
      <div className="row-2">
        <div className="field">
          <label className="field__label" htmlFor={`${id}-kind`}>{t('recurrence.kind')}</label>
          <select
            id={`${id}-kind`}
            className="field__select"
            value={recurrence.kind}
            onChange={e => {
              const kind = e.target.value as RecurrenceKind;
              setRule({ ...defaultRule(kind, today), dueWithinDays: recurrence.dueWithinDays ?? null }, true);
            }}
          >
            {RECURRENCE_KINDS.map(k => <option key={k} value={k}>{t(KIND_LABEL_KEYS[k])}</option>)}
          </select>
        </div>
        <div className="field">{primaryField()}</div>
      </div>

      {recurrence.kind === 'YEARLY' && (
        <div className="row-2">
          <div className="field">
            <label className="field__label" htmlFor={`${id}-yday`}>{t('recurrence.dayOfMonth')}</label>
            <select
              id={`${id}-yday`}
              className="field__select"
              value={recurrence.day ?? 1}
              onChange={e => setRule({ ...recurrence, day: Number(e.target.value) }, true)}
            >
              {range(daysInMonthOfYear(recurrence.month ?? 1)).map(d => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
        </div>
      )}

      <div className="row-2">
        <div className="field">
          <label className="field__label" htmlFor={`${id}-next`}>{t('recurrence.nextOn')}</label>
          <input
            id={`${id}-next`}
            className="field__input"
            type="date"
            value={relevantFrom}
            onChange={e => onChange({ recurrence, relevantFrom: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`${id}-due`}>{t('recurrence.dueWithin')}</label>
          <input
            id={`${id}-due`}
            className="field__input"
            type="number"
            min={0}
            max={365}
            value={recurrence.dueWithinDays ?? ''}
            placeholder={t('recurrence.dueWithinPlaceholder')}
            onChange={e => onChange({ recurrence: { ...recurrence, dueWithinDays: numberOrNull(e.target.value) }, relevantFrom })}
          />
        </div>
      </div>

      {error
        ? <p className="field__error">{error}</p>
        : isRuleValid(recurrence) && (
          <p className={styles.summary}>{recurrenceSummary(recurrence, relevantFrom || undefined)}</p>
        )}
    </>
  );
}

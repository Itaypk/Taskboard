import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { clearDeadlineMutes } from '../../api';
import { composeDigestCron, parseDigestCron, type DigestDay } from '../digestCron';
import { HelpTip } from '../HelpTip';
import { Toggle } from '../Toggle';
import { digestDaysInWeekOrder, shortDayName } from './schedule';
import type { SettingsAutosave } from './useSettingsAutosave';
import styles from './SettingsSections.module.css';

interface Props {
  autosave: SettingsAutosave;
  /** The instance has AI; without it, reminders can't be written by the assistant. */
  instanceAi: boolean;
  telegramChatReady: boolean;
}

/** What reaches the user unprompted: the reminder before a planned task, and the daily digest. */
export function RemindersSection({ autosave, instanceAi, telegramChatReady }: Props) {
  const { t, i18n } = useTranslation();
  const { form, update } = autosave;
  const digest = parseDigestCron(form.dailyDigestCron);
  const [clearingMutes, setClearingMutes] = useState(false);
  const [mutesCleared, setMutesCleared] = useState(false);

  const toggleDigestDay = (day: DigestDay) => {
    const days = digest.days.includes(day) ? digest.days.filter(d => d !== day) : [...digest.days, day];
    // The last selected day can't be cleared (its chip is disabled); turning the digest off is the toggle's job.
    if (days.length === 0) return;
    update({ dailyDigestCron: composeDigestCron({ ...digest, days }) });
  };

  const handleClearMutes = async () => {
    setClearingMutes(true);
    try {
      await clearDeadlineMutes();
      setMutesCleared(true);
    } catch (err) {
      console.error(err);
    } finally {
      setClearingMutes(false);
    }
  };

  return (
    <>
      <div className="field">
        <label className="field__label">{t('settingsModal.reminders.beforeTask')}</label>
        <Toggle
          checked={form.appReminders}
          onChange={next => update({ appReminders: next })}
          label={t('settingsModal.reminders.sendReminder')}
        />
        <p className={`settings-hint ${styles.indented}`}>{t('settingsModal.reminders.sentOverTelegram')}</p>
        {instanceAi && (
          <div className={styles.toggleWithHelp}>
            <Toggle
              checked={form.aiEnhancedReminders}
              onChange={next => update({ aiEnhancedReminders: next })}
              label={t('settingsModal.assistant.remindersToggle')}
              disabled={!form.aiEnabled || !form.appReminders}
            />
            <HelpTip text={t('settingsModal.assistant.remindersHelp')} />
          </div>
        )}
      </div>

      <div className="field">
        <label className="field__label">
          {t('settingsModal.general.dailyDigest')}
          <HelpTip text={t('settingsModal.general.dailyDigestHelp')} />
        </label>
        <Toggle
          checked={form.dailyDigestEnabled}
          onChange={next => update({ dailyDigestEnabled: next })}
          label={t('settingsModal.general.dailyDigestToggle')}
        />
        {form.dailyDigestEnabled && (
          <div className={styles.indented}>
            <div className={styles.inlineRow}>
              <div className={styles.dayChips} role="group" aria-label={t('settingsModal.general.dailyDigestDays')}>
                {digestDaysInWeekOrder(form.weekStartDay).map(day => {
                  const selected = digest.days.includes(day);
                  return (
                    <button
                      key={day}
                      type="button"
                      className={`${styles.dayChip} ${selected ? styles.dayChipSelected : ''}`}
                      aria-pressed={selected}
                      disabled={selected && digest.days.length === 1}
                      onClick={() => toggleDigestDay(day)}
                    >
                      {shortDayName(day, i18n.language)}
                    </button>
                  );
                })}
              </div>
              <input
                aria-label={t('settingsModal.general.dailyDigestTime')}
                type="time"
                className="field__input"
                value={digest.time}
                onChange={e => e.target.value && update({ dailyDigestCron: composeDigestCron({ ...digest, time: e.target.value }) })}
              />
            </div>
            <Toggle
              checked={form.dailyDigestDueTasks}
              onChange={next => update({ dailyDigestDueTasks: next })}
              label={t('settingsModal.general.dailyDigestDueTasks')}
            />
            {!telegramChatReady && (
              <p className="settings-hint">{t('settingsModal.general.dailyDigestUnreachable')}</p>
            )}
          </div>
        )}
        <p className="settings-hint">
          {t('settingsModal.reminders.mutedPrompt')}{' '}
          <button
            type="button"
            className="link-btn"
            onClick={handleClearMutes}
            disabled={clearingMutes || mutesCleared}
          >
            {t('settingsModal.general.clearDeadlineMutes')}
          </button>
          <HelpTip text={t('settingsModal.general.clearDeadlineMutesHelp')} />
        </p>
        {mutesCleared && (
          <p className="settings-hint" role="status">{t('settingsModal.general.deadlineMutesCleared')}</p>
        )}
      </div>
    </>
  );
}

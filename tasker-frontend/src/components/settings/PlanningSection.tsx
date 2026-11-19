import { useTranslation } from 'react-i18next';
import type { SettingsOptions } from '../../types';
import { HelpTip } from '../HelpTip';
import { Toggle } from '../Toggle';
import { composePlanningCron, DAY_VALUES, parsePlanningCron } from './schedule';
import type { SettingsAutosave } from './useSettingsAutosave';
import styles from './SettingsSections.module.css';

interface Props {
  autosave: SettingsAutosave;
  options: SettingsOptions | null;
  /** Telegram can message the user; the session starts there, so without it it never arrives. */
  telegramChatReady: boolean;
  /** Opens the AI section, where AI access is turned back on. */
  onOpenAi: () => void;
}

/** The weekly planning session, and what the planner should know about the user. Needs AI. */
export function PlanningSection({ autosave, options, telegramChatReady, onOpenAi }: Props) {
  const { t } = useTranslation();
  const { form, update, save } = autosave;
  const planning = parsePlanningCron(form.planningCron);
  const planningEnabled = planning.day !== '';

  return (
    <>
      {!form.aiEnabled && (
        <p className="settings-hint">
          {t('settingsModal.planning.aiOff')}{' '}
          <button type="button" className="link-btn" onClick={onOpenAi}>{t('settingsModal.planning.aiOffLink')}</button>
        </p>
      )}
      <fieldset className={styles.fieldset} disabled={!form.aiEnabled}>
        <div className="field">
          <label className="field__label">
            {t('settingsModal.assistant.weeklyPlanning')}
            <HelpTip text={t('settingsModal.assistant.weeklyPlanningHelp')} />
          </label>
          <Toggle
            checked={planningEnabled}
            onChange={next => update({ planningCron: next ? composePlanningCron('MON', planning.time) : null })}
            label={t('settingsModal.assistant.weeklyPlanningToggle')}
          />
          {planningEnabled && (
            <div className={styles.inlineRow}>
              <select
                aria-label={t('settingsModal.assistant.planningDay')}
                className="field__input"
                value={planning.day}
                onChange={e => update({ planningCron: composePlanningCron(e.target.value, planning.time) })}
              >
                {DAY_VALUES.map(d => (
                  <option key={d.value} value={d.cron}>{t(d.labelKey)}</option>
                ))}
              </select>
              <input
                aria-label={t('settingsModal.assistant.planningTime')}
                type="time"
                className="field__input"
                value={planning.time}
                onChange={e => e.target.value && update({ planningCron: composePlanningCron(planning.day, e.target.value) })}
              />
            </div>
          )}
          {planningEnabled && !telegramChatReady && (
            <p className="settings-hint">{t('settingsModal.assistant.weeklyPlanningUnreachable')}</p>
          )}
        </div>

        <div className="field">
          <label className="field__label" htmlFor="settings-context">
            {t('settingsModal.assistant.personalContext')}
            <HelpTip text={t('settingsModal.assistant.personalContextHelp')} />
          </label>
          <textarea
            id="settings-context"
            className="field__textarea"
            value={form.contextBlock}
            onChange={e => update({ contextBlock: e.target.value }, { save: false })}
            onBlur={() => void save()}
            rows={5}
            placeholder={t('settingsModal.assistant.personalContextPlaceholder')}
          />
        </div>

        <div className="field">
          <label className="field__label" htmlFor="settings-gender">
            {t('settingsModal.assistant.addressYou')}
            <HelpTip text={t('settingsModal.assistant.addressYouHelp')} />
          </label>
          <select
            id="settings-gender"
            className="field__select"
            value={form.gender ?? ''}
            onChange={e => update({ gender: e.target.value || undefined })}
          >
            <option value="">{t('settingsModal.assistant.noPreference')}</option>
            {(options?.genders ?? []).map(opt => (
              <option key={opt.code} value={opt.code}>{opt.label}</option>
            ))}
          </select>
        </div>
      </fieldset>
    </>
  );
}

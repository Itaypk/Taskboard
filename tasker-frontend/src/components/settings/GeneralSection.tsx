import { useTranslation } from 'react-i18next';
import type { SettingsOptions } from '../../types';
import { HelpTip } from '../HelpTip';
import { DAY_VALUES } from './schedule';
import type { SettingsAutosave } from './useSettingsAutosave';
import styles from './SettingsSections.module.css';

interface Props {
  autosave: SettingsAutosave;
  options: SettingsOptions | null;
}

/** Name, language and region, and how long done tasks stay on the board. */
export function GeneralSection({ autosave, options }: Props) {
  const { t } = useTranslation();
  const { form, update, save, lastSaved } = autosave;

  // The server only accepts a zone from its list, so a half-typed or misspelled one goes back
  // to the saved value rather than failing a save.
  const commitTimeZone = () => {
    if (options && !options.timeZones.includes(form.timeZone)) {
      update({ timeZone: lastSaved().timeZone }, { save: false });
      return;
    }
    void save();
  };

  const commitAutoArchive = () => {
    const days = form.autoArchiveDays;
    if (days != null && days < 1) update({ autoArchiveDays: null }, { save: false });
    void save();
  };

  return (
    <>
      <div className="field">
        <label className="field__label" htmlFor="settings-name">{t('settingsModal.general.displayName')}</label>
        <input
          id="settings-name"
          className="field__input"
          value={form.displayName}
          onChange={e => update({ displayName: e.target.value }, { save: false })}
          onBlur={() => void save()}
          placeholder={t('settingsModal.general.displayNamePlaceholder')}
        />
      </div>

      <div className="field">
        <label className="field__label" htmlFor="settings-lang">{t('settingsModal.general.language')}</label>
        <select
          id="settings-lang"
          className="field__input"
          value={form.preferredLanguage}
          onChange={e => update({ preferredLanguage: e.target.value })}
        >
          {(options?.languages ?? []).map(opt => (
            <option key={opt.code} value={opt.code}>{opt.label}</option>
          ))}
        </select>
      </div>

      <div className={styles.row}>
        <div className="field">
          <label className="field__label" htmlFor="settings-tz">{t('settingsModal.general.timeZone')}</label>
          <input
            id="settings-tz"
            className="field__input"
            list="settings-tz-list"
            value={form.timeZone}
            onChange={e => update({ timeZone: e.target.value }, { save: false })}
            onBlur={commitTimeZone}
            placeholder={t('settingsModal.general.timeZonePlaceholder')}
            autoComplete="off"
          />
          {options && (
            <datalist id="settings-tz-list">
              {options.timeZones.map(tz => <option key={tz} value={tz} />)}
            </datalist>
          )}
        </div>

        <div className="field">
          <label className="field__label" htmlFor="settings-week-start">
            {t('settingsModal.general.firstDayOfWeek')}
            <HelpTip text={t('settingsModal.general.firstDayOfWeekHelp')} />
          </label>
          <select
            id="settings-week-start"
            className="field__input"
            value={form.weekStartDay ?? ''}
            onChange={e => update({ weekStartDay: e.target.value || null })}
          >
            <option value="">{t('settingsModal.general.notSet')}</option>
            {DAY_VALUES.map(d => (
              <option key={d.value} value={d.value}>{t(d.labelKey)}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="field">
        <label className="field__label" htmlFor="settings-auto-archive">
          {t('settingsModal.general.autoArchive')}
          <HelpTip text={t('settingsModal.general.autoArchiveHelp')} />
        </label>
        <input
          id="settings-auto-archive"
          className={`field__input ${styles.archiveDaysInput}`}
          type="number"
          min={1}
          value={form.autoArchiveDays ?? ''}
          onChange={e => update({ autoArchiveDays: e.target.value ? Number(e.target.value) : null }, { save: false })}
          onBlur={commitAutoArchive}
          placeholder={t('settingsModal.general.autoArchivePlaceholder')}
        />
      </div>
    </>
  );
}

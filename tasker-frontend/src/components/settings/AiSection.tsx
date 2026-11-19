import { useTranslation } from 'react-i18next';
import { AiUsageMeter } from '../AiUsageMeter';
import { HelpTip } from '../HelpTip';
import { Toggle } from '../Toggle';
import type { SettingsAutosave } from './useSettingsAutosave';

/** The account-wide AI opt-out, and how much of the AI budget is used. */
export function AiSection({ autosave }: { autosave: SettingsAutosave }) {
  const { t } = useTranslation();
  const { form, update } = autosave;
  return (
    <>
      <div className="field">
        <label className="field__label">
          {t('settingsModal.assistant.aiAccess')}
          <HelpTip side="start" text={t('settingsModal.assistant.aiAccessHelp')} />
        </label>
        <Toggle
          checked={form.aiEnabled}
          onChange={next => update({ aiEnabled: next })}
          label={t('settingsModal.assistant.aiAccessToggle')}
          disabled={!form.aiTierGrantsAccess}
        />
      </div>
      <div className="field">
        <label className="field__label">
          {t('settingsModal.assistant.aiPlan')}
          <HelpTip side="start" text={t('settingsModal.assistant.aiPlanHelp')} />
        </label>
        <AiUsageMeter />
      </div>
    </>
  );
}

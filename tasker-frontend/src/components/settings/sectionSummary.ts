import type { TFunction } from 'i18next';
import type { SettingsSection } from '../../taskLink';
import type { SettingsOptions, UserSettings } from '../../types';
import { parseDigestCron } from '../digestCron';
import { DAY_VALUES, formatClockTime, parsePlanningCron } from './schedule';

export interface SummaryContext {
  t: TFunction;
  language: string;
  options: SettingsOptions | null;
  /** Linked login providers, or null while they load. */
  providers: string[] | null;
  calendarDelivery: UserSettings['calendarDelivery'];
  inboundMail: boolean;
}

const join = (parts: (string | null | undefined | false)[]) => parts.filter(Boolean).join(' · ');

/**
 * One line under each section in the phone's section list, saying what is set there now, so the
 * list answers "where is it?" and "is it on?" before anything is opened. Wider screens show the
 * section itself next to the list and don't use these.
 */
export function sectionSummary(section: SettingsSection, form: UserSettings, ctx: SummaryContext): string {
  const { t } = ctx;
  switch (section) {
    case 'general': {
      const language = ctx.options?.languages.find(l => l.code === form.preferredLanguage)?.label;
      return join([form.displayName.trim(), language, form.timeZone]);
    }
    case 'signin': {
      if (ctx.providers === null) return t('settingsModal.summary.signin');
      if (ctx.providers.length === 0) return t('settingsModal.summary.signinNone');
      return join(ctx.providers.map(p => t(`connectedAccounts.providers.${p}`, { defaultValue: p })));
    }
    case 'planning': {
      const { day, time } = parsePlanningCron(form.planningCron);
      const dayKey = DAY_VALUES.find(d => d.cron === day)?.labelKey;
      if (!form.aiEnabled || !dayKey) return t('settingsModal.summary.planningOff');
      return t('settingsModal.summary.planningOn', { day: t(dayKey), time: formatClockTime(time, ctx.language) });
    }
    case 'reminders': {
      const digest = parseDigestCron(form.dailyDigestCron);
      return join([
        t(form.appReminders ? 'settingsModal.summary.remindersOn' : 'settingsModal.summary.remindersOff'),
        form.dailyDigestEnabled
          ? t('settingsModal.summary.digestOn', { time: formatClockTime(digest.time, ctx.language) })
          : t('settingsModal.summary.digestOff'),
      ]);
    }
    case 'calendar':
      return t(`calendarDelivery.outgoing.options.${ctx.calendarDelivery}`);
    case 'ai':
      return t(form.aiEnabled ? 'settingsModal.summary.aiOn' : 'settingsModal.summary.aiOff');
    case 'connections':
      return t(ctx.inboundMail ? 'settingsModal.summary.connections' : 'settingsModal.summary.connectionsApiOnly');
    case 'data':
      return t('settingsModal.summary.data');
  }
}

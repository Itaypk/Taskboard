import { useState, useEffect, useMemo, useRef, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './SettingsModal.module.css';
import type { UserSettings, SettingsOptions } from '../types';
import { AiUsageMeter } from './AiUsageMeter';
import { ActiveSessions } from './ActiveSessions';
import { ConnectedAccounts } from './ConnectedAccounts';
import { useAuth } from '../auth/AuthContext';
import { ImportResultDialog } from './ImportResultDialog';
import { categorizeImportError, type ImportResult } from './importResult';
import { HelpTip } from './HelpTip';
import { ApiTokens } from './ApiTokens';
import { Tabs } from './Tabs';
import { Toggle } from './Toggle';
import { updateUserSettings, fetchSettingsOptions, deleteAccount, exportAccount, importAccount, requestEmailVerification, clearDeadlineMutes } from '../api';
import { DIGEST_DAYS, composeDigestCron, parseDigestCron, type DigestDay } from './digestCron';
import type { ImportSummary } from '../api';
import { applyLocale } from '../i18n';
import type { SettingsTab } from '../taskLink';

interface SettingsModalProps {
  settings: UserSettings;
  open: boolean;
  /** Tab to show; tracks the `/settings/<tab>` route so deep links land on the right section. */
  initialTab?: SettingsTab;
  onClose: () => void;
  onSave: (s: UserSettings) => void;
  onAccountDeleted: () => void;
}

const SETTINGS_TAB_IDS = ['general', 'assistant', 'integrations'] as const;
const SETTINGS_TAB_LABEL_KEYS: Record<(typeof SETTINGS_TAB_IDS)[number], string> = {
  general: 'settingsModal.tabs.general',
  assistant: 'settingsModal.tabs.assistant',
  integrations: 'settingsModal.tabs.integrations',
};

const DAY_VALUES: { value: string; cron: string; labelKey: string }[] = [
  { value: 'MONDAY', cron: 'MON', labelKey: 'settingsModal.days.monday' },
  { value: 'TUESDAY', cron: 'TUE', labelKey: 'settingsModal.days.tuesday' },
  { value: 'WEDNESDAY', cron: 'WED', labelKey: 'settingsModal.days.wednesday' },
  { value: 'THURSDAY', cron: 'THU', labelKey: 'settingsModal.days.thursday' },
  { value: 'FRIDAY', cron: 'FRI', labelKey: 'settingsModal.days.friday' },
  { value: 'SATURDAY', cron: 'SAT', labelKey: 'settingsModal.days.saturday' },
  { value: 'SUNDAY', cron: 'SUN', labelKey: 'settingsModal.days.sunday' },
];

// Spring CronExpression: "second minute hour day-of-month month day-of-week"
function composeCron(day: string, time: string): string | null {
  if (!day || !time) return null;
  const [hh, mm] = time.split(':');
  if (hh == null || mm == null) return null;
  return `0 ${Number(mm)} ${Number(hh)} * * ${day}`;
}

/** Digest day chips in the user's week order, starting from their configured week-start day. */
function digestDaysInWeekOrder(weekStartDay: string | null | undefined): DigestDay[] {
  const start = DAY_VALUES.findIndex(d => d.value === weekStartDay);
  if (start <= 0) return [...DIGEST_DAYS];
  return [...DIGEST_DAYS.slice(start), ...DIGEST_DAYS.slice(0, start)];
}

/** Short localized weekday name ("Mon", "ב׳", …). 2024-01-01 was a Monday. */
function shortDayName(day: DigestDay, language: string): string {
  const date = new Date(Date.UTC(2024, 0, 1 + DIGEST_DAYS.indexOf(day)));
  return new Intl.DateTimeFormat(language, { weekday: 'short', timeZone: 'UTC' }).format(date);
}

function parseCron(cron: string | null | undefined): { day: string; time: string } {
  if (!cron) return { day: '', time: '09:00' };
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6) return { day: '', time: '09:00' };
  const [, minute, hour, , , dow] = parts;
  const h = Number(hour);
  const m = Number(minute);
  if (Number.isNaN(h) || Number.isNaN(m)) return { day: '', time: '09:00' };
  const time = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  return { day: dow.toUpperCase(), time };
}

export function SettingsModal({ settings, open, initialTab, onClose, onSave, onAccountDeleted }: SettingsModalProps) {
  const { t, i18n } = useTranslation();
  const settingsTabs = SETTINGS_TAB_IDS.map(id => ({ id, label: t(SETTINGS_TAB_LABEL_KEYS[id]) }));
  const daysOfWeek = DAY_VALUES.map(d => ({ value: d.value, cron: d.cron, label: t(d.labelKey) }));
  const [form, setForm] = useState<UserSettings>(settings);
  const { state: authState } = useAuth();
  // Weekly planning is pushed over Telegram, which needs a chat the user has opened — not just a link.
  const telegramChatReady = authState.status === 'authenticated' && authState.user.telegramChatReady;
  const [activeTab, setActiveTab] = useState<SettingsTab>(initialTab ?? 'general');
  const [lastInitialTab, setLastInitialTab] = useState(initialTab);
  const [wasOpen, setWasOpen] = useState(open);
  const [saving, setSaving] = useState(false);
  const [options, setOptions] = useState<SettingsOptions | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [emailInput, setEmailInput] = useState(settings.email ?? '');
  const [verificationSent, setVerificationSent] = useState(false);
  const [sendingVerification, setSendingVerification] = useState(false);
  const [clearingMutes, setClearingMutes] = useState(false);
  const [mutesCleared, setMutesCleared] = useState(false);

  if (open && !wasOpen) {
    setWasOpen(true);
    setForm(settings);
    setEmailInput(settings.email ?? '');
    setDeleteConfirm(false);
    setVerificationSent(false);
    setMutesCleared(false);
    setActiveTab(initialTab ?? 'general');
    setLastInitialTab(initialTab);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  } else if (open && initialTab && initialTab !== lastInitialTab) {
    // The `/settings/<tab>` route changed while the modal was already open (the open-edge above
    // only fires on closed→open). Track the applied value so manual tab clicks aren't overridden.
    setLastInitialTab(initialTab);
    setActiveTab(initialTab);
  }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (deleteConfirm) { setDeleteConfirm(false); return; }
        onClose();
      }
    };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose, deleteConfirm]);

  useEffect(() => {
    if (open && !options) {
      fetchSettingsOptions().then(setOptions).catch(console.error);
    }
  }, [open, options]);

  const planningParts = useMemo(() => parseCron(form.planningCron), [form.planningCron]);
  const planningEnabled = planningParts.day !== '';
  const digestSchedule = useMemo(() => parseDigestCron(form.dailyDigestCron), [form.dailyDigestCron]);

  const toggleDigestDay = (day: DigestDay) => {
    const days = digestSchedule.days.includes(day)
      ? digestSchedule.days.filter(d => d !== day)
      : [...digestSchedule.days, day];
    // The last selected day can't be cleared (its chip is disabled); turning the digest off is the toggle's job.
    if (days.length === 0) return;
    setForm(f => ({ ...f, dailyDigestCron: composeDigestCron({ ...digestSchedule, days }) }));
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

  const handleSaveClick = async () => {
    setSaving(true);
    try {
      await updateUserSettings({
        displayName: form.displayName,
        contextBlock: form.contextBlock,
        timeZone: form.timeZone,
        preferredLanguage: form.preferredLanguage,
        calendarInviteEmail: form.calendarInviteEmail,
        appReminders: form.appReminders,
        gender: form.gender,
        agentDescription: form.agentDescription,
        planningCron: form.planningCron ?? null,
        weekStartDay: form.weekStartDay ?? null,
        autoArchiveDays: form.autoArchiveDays ?? null,
        aiEnabled: form.aiEnabled,
        aiEnhancedReminders: form.aiEnhancedReminders,
        dailyDigestEnabled: form.dailyDigestEnabled,
        dailyDigestDueTasks: form.dailyDigestDueTasks,
        dailyDigestCron: form.dailyDigestCron,
      });

      onSave(form);
      // Apply a language change immediately (i18n UI language + document lang/dir + Intl locale),
      // no reload needed (docs/I18N.md, D3). Live since the `he` launch.
      void applyLocale(form.preferredLanguage);
      onClose();
    } catch (e) {
      console.error('Failed to save settings', e);
    } finally {
      setSaving(false);
    }
  };

  const handleSendVerification = async () => {
    if (!emailInput.trim()) return;
    setSendingVerification(true);
    try {
      await requestEmailVerification(emailInput.trim());
      setVerificationSent(true);
    } catch (e) {
      console.error('Failed to send verification email', e);
    } finally {
      setSendingVerification(false);
    }
  };

  const handleExport = async () => {
    setExporting(true);
    try {
      await exportAccount();
    } catch (e) {
      console.error('Export failed', e);
    } finally {
      setExporting(false);
    }
  };

  const handleImportClick = () => {
    setImportResult(null);
    importInputRef.current?.click();
  };

  const handleImportFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Always clear the input so picking the same file twice still triggers onChange.
    e.target.value = '';
    if (!file) return;
    setImporting(true);
    setImportResult(null);
    try {
      const text = await file.text();
      let payload: unknown;
      try {
        payload = JSON.parse(text);
      } catch {
        // We can't even parse the file locally — it's a corrupted/unsupported file.
        setImportResult({ kind: 'error', category: 'CORRUPTED_FILE', detail: 'The file is not valid JSON.' });
        return;
      }
      const summary: ImportSummary = await importAccount(payload);
      setImportResult({ kind: 'success', summary });
    } catch (err: unknown) {
      console.error('Import failed', err);
      setImportResult({ kind: 'error', ...categorizeImportError(err) });
    } finally {
      setImporting(false);
    }
  };

  const handleImportResultClose = () => {
    const wasSuccess = importResult?.kind === 'success';
    setImportResult(null);
    // Hard reload so the rest of the app re-fetches against the freshly populated account.
    if (wasSuccess) window.location.reload();
  };

  const handleDeleteConfirmed = async () => {
    setDeleting(true);
    try {
      await deleteAccount();
      onAccountDeleted();
    } catch (e) {
      console.error('Account deletion failed', e);
      setDeleting(false);
      setDeleteConfirm(false);
    }
  };

  const genderOptions = options?.genders ?? [];

  return (
    <div
      className={`modal-overlay modal-overlay--top${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={t('settingsModal.title')}>
        <div className="modal__header">
          <span className="modal__title">{t('settingsModal.title')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('settingsModal.close')}>×</button>
        </div>

        <Tabs
          tabs={settingsTabs}
          activeTab={activeTab}
          onChange={id => setActiveTab(id as SettingsTab)}
        />

        <div className="modal__body">
          {activeTab === 'general' && (
            <>
              <div className="field">
                <label className="field__label">{t('settingsModal.general.displayName')}</label>
                <input
                  className="field__input"
                  value={form.displayName}
                  onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                  placeholder={t('settingsModal.general.displayNamePlaceholder')}
                />
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-email">{t('settingsModal.general.emailAddress')}</label>
                {settings.emailVerified && settings.email && settings.email === emailInput.trim() ? (
                  <p className="settings-hint settings-hint--verified">
                    {t('settingsModal.general.emailVerified', { email: settings.email })}
                  </p>
                ) : (
                  <p className="settings-hint">
                    {t('settingsModal.general.emailHint')}
                    {settings.email && !settings.emailVerified && ` ${t('settingsModal.general.emailNotVerified')}`}
                  </p>
                )}
                <div className="settings-email-row">
                  <input
                    id="settings-email"
                    className="field__input"
                    type="email"
                    value={emailInput}
                    onChange={e => { setEmailInput(e.target.value); setVerificationSent(false); }}
                    placeholder={t('settingsModal.general.emailPlaceholder')}
                    autoComplete="email"
                  />
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleSendVerification}
                    disabled={
                      sendingVerification ||
                      !emailInput.trim() ||
                      (settings.emailVerified && settings.email === emailInput.trim())
                    }
                  >
                    {sendingVerification ? t('settingsModal.general.sending') : verificationSent ? t('settingsModal.general.sent') : t('settingsModal.general.sendVerification')}
                  </button>
                </div>
                {verificationSent && (
                  <p className="settings-hint">{t('settingsModal.general.verificationSentHint')}</p>
                )}
              </div>

              <ConnectedAccounts />

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
                  onChange={e => setForm(f => ({
                    ...f,
                    autoArchiveDays: e.target.value ? Number(e.target.value) : null,
                  }))}
                  placeholder={t('settingsModal.general.autoArchivePlaceholder')}
                />
              </div>

              <div className="field">
                <label className="field__label">{t('settingsModal.general.notifications')}</label>
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={form.calendarInviteEmail}
                    onChange={e => setForm(f => ({ ...f, calendarInviteEmail: e.target.checked }))}
                    disabled={!settings.emailVerified}
                  />
                  <span>
                    {t('settingsModal.general.calendarInvites')}
                    {!settings.emailVerified && (
                      <span className="settings-hint"> {t('settingsModal.general.verifyEmailFirst')}</span>
                    )}
                  </span>
                </label>
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={form.appReminders}
                    onChange={e => setForm(f => ({ ...f, appReminders: e.target.checked }))}
                  />
                  <span>
                    {t('settingsModal.general.appReminders')}
                    <span className="settings-hint"> {t('settingsModal.general.sentOverTelegram')}</span>
                  </span>
                </label>
              </div>

              <div className="field">
                <label className="field__label">
                  {t('settingsModal.general.dailyDigest')}
                  <HelpTip text={t('settingsModal.general.dailyDigestHelp')} />
                </label>
                <Toggle
                  checked={form.dailyDigestEnabled}
                  onChange={next => setForm(f => ({ ...f, dailyDigestEnabled: next }))}
                  label={t('settingsModal.general.dailyDigestToggle')}
                />
                {form.dailyDigestEnabled && (
                  <>
                    <div className={styles.planningRow}>
                      <div className={styles.dayChips} role="group" aria-label={t('settingsModal.general.dailyDigestDays')}>
                        {digestDaysInWeekOrder(form.weekStartDay).map(day => {
                          const selected = digestSchedule.days.includes(day);
                          return (
                            <button
                              key={day}
                              type="button"
                              className={`${styles.dayChip} ${selected ? styles.dayChipSelected : ''}`}
                              aria-pressed={selected}
                              disabled={selected && digestSchedule.days.length === 1}
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
                        value={digestSchedule.time}
                        onChange={e => e.target.value && setForm(f => ({
                          ...f,
                          dailyDigestCron: composeDigestCron({ ...digestSchedule, time: e.target.value }),
                        }))}
                      />
                    </div>
                    <label className="settings-toggle">
                      <input
                        type="checkbox"
                        checked={form.dailyDigestDueTasks}
                        onChange={e => setForm(f => ({ ...f, dailyDigestDueTasks: e.target.checked }))}
                      />
                      <span>{t('settingsModal.general.dailyDigestDueTasks')}</span>
                    </label>
                    {!telegramChatReady && (
                      <p className="settings-hint">{t('settingsModal.general.dailyDigestUnreachable')}</p>
                    )}
                  </>
                )}
                <div className={styles.planningRow}>
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleClearMutes}
                    disabled={clearingMutes || mutesCleared}
                  >
                    {t('settingsModal.general.clearDeadlineMutes')}
                  </button>
                  <HelpTip text={t('settingsModal.general.clearDeadlineMutesHelp')} />
                  {mutesCleared && (
                    <span className="settings-hint" role="status">{t('settingsModal.general.deadlineMutesCleared')}</span>
                  )}
                </div>
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-tz">{t('settingsModal.general.timeZone')}</label>
                <input
                  id="settings-tz"
                  className="field__input"
                  list="settings-tz-list"
                  value={form.timeZone}
                  onChange={e => setForm(f => ({ ...f, timeZone: e.target.value }))}
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
                <label className="field__label" htmlFor="settings-lang">{t('settingsModal.general.language')}</label>
                <select
                  id="settings-lang"
                  className="field__input"
                  value={form.preferredLanguage}
                  onChange={e => setForm(f => ({ ...f, preferredLanguage: e.target.value }))}
                >
                  {(options?.languages ?? []).map(opt => (
                    <option key={opt.code} value={opt.code}>{opt.label}</option>
                  ))}
                </select>
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
                  onChange={e => setForm(f => ({ ...f, weekStartDay: e.target.value || null }))}
                >
                  <option value="">{t('settingsModal.general.notSet')}</option>
                  {daysOfWeek.map(d => (
                    <option key={d.value} value={d.value}>{d.label}</option>
                  ))}
                </select>
              </div>

              <ActiveSessions />

              <div className="danger-zone">
                <p className="danger-zone__label">{t('settingsModal.general.dangerZone')}</p>
                <div className="danger-zone__actions">
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleExport}
                    disabled={exporting || deleting || importing}
                  >
                    {exporting ? t('settingsModal.general.exporting') : t('settingsModal.general.exportData')}
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleImportClick}
                    disabled={exporting || deleting || importing}
                  >
                    {importing ? t('settingsModal.general.importing') : t('settingsModal.general.importData')}
                  </button>
                  <input
                    ref={importInputRef}
                    type="file"
                    accept="application/json"
                    style={{ display: 'none' }}
                    onChange={handleImportFile}
                  />
                  {deleteConfirm ? (
                    <div className="danger-zone__confirm">
                      <span className="danger-zone__confirm-text">{t('settingsModal.general.deleteWarning')}</span>
                      <button
                        type="button"
                        className="btn btn--danger-solid"
                        onClick={handleDeleteConfirmed}
                        disabled={deleting}
                      >
                        {deleting ? t('settingsModal.general.deleting') : t('settingsModal.general.confirmDelete')}
                      </button>
                      <button
                        type="button"
                        className="btn btn--ghost"
                        onClick={() => setDeleteConfirm(false)}
                        disabled={deleting}
                      >
                        {t('settingsModal.general.cancel')}
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="btn btn--danger"
                      onClick={() => setDeleteConfirm(true)}
                      disabled={saving}
                    >
                      {t('settingsModal.general.deleteAccount')}
                    </button>
                  )}
                </div>
              </div>
            </>
          )}

          {activeTab === 'assistant' && (
            <>
              <div className="field">
                <label className="field__label">
                  {t('settingsModal.assistant.aiAccess')}
                  <HelpTip
                    side="start"
                    text={t('settingsModal.assistant.aiAccessHelp')}
                  />
                </label>
                <Toggle
                  checked={form.aiEnabled}
                  onChange={next => setForm(f => ({ ...f, aiEnabled: next }))}
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

              <fieldset className={styles.aiFieldset} disabled={!form.aiEnabled}>
              <div className="field">
                <label className="field__label">
                  {t('settingsModal.assistant.weeklyPlanning')}
                  <HelpTip text={t('settingsModal.assistant.weeklyPlanningHelp')} />
                </label>
                <Toggle
                  checked={planningEnabled}
                  onChange={next => setForm(f => ({
                    ...f,
                    planningCron: next ? composeCron('MON', planningParts.time) : null,
                  }))}
                  label={t('settingsModal.assistant.weeklyPlanningToggle')}
                />
                {planningEnabled && (
                  <div className={styles.planningRow}>
                    <select
                      aria-label={t('settingsModal.assistant.planningDay')}
                      className="field__input"
                      value={planningParts.day}
                      onChange={e => setForm(f => ({ ...f, planningCron: composeCron(e.target.value, planningParts.time) }))}
                    >
                      {daysOfWeek.map(d => (
                        <option key={d.value} value={d.cron}>{d.label}</option>
                      ))}
                    </select>
                    <input
                      aria-label={t('settingsModal.assistant.planningTime')}
                      type="time"
                      className="field__input"
                      value={planningParts.time}
                      onChange={e => setForm(f => ({ ...f, planningCron: composeCron(planningParts.day, e.target.value) }))}
                    />
                  </div>
                )}
                {planningEnabled && !telegramChatReady && (
                  <p className="settings-hint">{t('settingsModal.assistant.weeklyPlanningUnreachable')}</p>
                )}
              </div>

              <div className="field">
                <label className="field__label">
                  {t('settingsModal.assistant.reminders')}
                  <HelpTip text={t('settingsModal.assistant.remindersHelp')} />
                </label>
                <Toggle
                  checked={form.aiEnhancedReminders}
                  onChange={next => setForm(f => ({ ...f, aiEnhancedReminders: next }))}
                  label={t('settingsModal.assistant.remindersToggle')}
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
                  onChange={e => setForm(f => ({ ...f, gender: e.target.value || undefined }))}
                >
                  <option value="">{t('settingsModal.assistant.noPreference')}</option>
                  {genderOptions.map(opt => (
                    <option key={opt.code} value={opt.code}>{opt.label}</option>
                  ))}
                </select>
              </div>

              <div className="field">
                <label className="field__label">
                  {t('settingsModal.assistant.personalContext')}
                  <HelpTip text={t('settingsModal.assistant.personalContextHelp')} />
                </label>
                <textarea
                  className="field__textarea"
                  value={form.contextBlock}
                  onChange={e => setForm(f => ({ ...f, contextBlock: e.target.value }))}
                  rows={6}
                  placeholder={t('settingsModal.assistant.personalContextPlaceholder')}
                />
              </div>
              </fieldset>
            </>
          )}

          {activeTab === 'integrations' && <ApiTokens />}
        </div>

        <div className="modal__footer">
          <button className="btn btn--ghost" onClick={onClose} disabled={saving}>{t('settingsModal.footer.cancel')}</button>
          <button className="btn btn--primary" onClick={handleSaveClick} disabled={saving}>
            {saving ? t('settingsModal.footer.saving') : t('settingsModal.footer.save')}
          </button>
        </div>
      </div>
      <ImportResultDialog result={importResult} onClose={handleImportResultClose} />
    </div>
  );
}

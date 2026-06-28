import { useState, useEffect, useMemo, useRef, type ChangeEvent } from 'react';
import styles from './SettingsModal.module.css';
import type { UserSettings, Task, SettingsOptions, Tag } from '../types';
import { AiUsageMeter } from './AiUsageMeter';
import { CategoryEditor } from './CategoryEditor';
import { TagEditor } from './TagEditor';
import { ConnectedAccounts } from './ConnectedAccounts';
import { ImportResultDialog } from './ImportResultDialog';
import { categorizeImportError, type ImportResult } from './importResult';
import { HelpTip } from './HelpTip';
import { Tabs } from './Tabs';
import { Toggle } from './Toggle';
import { createCategory, updateCategory, deleteCategory, updateTag, deleteTag, updateUserSettings, fetchSettingsOptions, deleteAccount, exportAccount, importAccount, requestEmailVerification } from '../api';
import type { ImportSummary } from '../api';
import type { SettingsTab } from '../taskLink';

interface SettingsModalProps {
  /** Categories and tags are board-owned, so their edits go to the active board. */
  boardId: string;
  settings: UserSettings;
  tasks: Task[];
  /** Board tags (popularity-ordered, with usage counts) for the labeling tab's tag manager. */
  tags: Tag[];
  open: boolean;
  /** Tab to show; tracks the `/settings/<tab>` route so deep links land on the right section. */
  initialTab?: SettingsTab;
  onClose: () => void;
  onSave: (s: UserSettings) => void;
  /** Called after tags were renamed/recolored/deleted on save, so the shell refetches tags and tasks. */
  onTagsChanged: () => void;
  onAccountDeleted: () => void;
}

const SETTINGS_TABS = [
  { id: 'general', label: 'General' },
  { id: 'categories', label: 'Labels' },
  { id: 'assistant', label: 'Assistant' },
];

const DAYS_OF_WEEK: { value: string; cron: string; label: string }[] = [
  { value: 'MONDAY', cron: 'MON', label: 'Monday' },
  { value: 'TUESDAY', cron: 'TUE', label: 'Tuesday' },
  { value: 'WEDNESDAY', cron: 'WED', label: 'Wednesday' },
  { value: 'THURSDAY', cron: 'THU', label: 'Thursday' },
  { value: 'FRIDAY', cron: 'FRI', label: 'Friday' },
  { value: 'SATURDAY', cron: 'SAT', label: 'Saturday' },
  { value: 'SUNDAY', cron: 'SUN', label: 'Sunday' },
];

// Spring CronExpression: "second minute hour day-of-month month day-of-week"
function composeCron(day: string, time: string): string | null {
  if (!day || !time) return null;
  const [hh, mm] = time.split(':');
  if (hh == null || mm == null) return null;
  return `0 ${Number(mm)} ${Number(hh)} * * ${day}`;
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

export function SettingsModal({ boardId, settings, tasks, tags, open, initialTab, onClose, onSave, onTagsChanged, onAccountDeleted }: SettingsModalProps) {
  const [form, setForm] = useState<UserSettings>(settings);
  // Staged tag edits (rename/recolor/delete), seeded from props on the open-edge and persisted on Save.
  const [tagDraft, setTagDraft] = useState<Tag[]>(tags);
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

  if (open && !wasOpen) {
    setWasOpen(true);
    setForm(settings);
    setTagDraft(tags);
    setEmailInput(settings.email ?? '');
    setDeleteConfirm(false);
    setVerificationSent(false);
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

  const usage = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const t of tasks) {
      counts[t.categoryId] = (counts[t.categoryId] ?? 0) + 1;
    }
    return counts;
  }, [tasks]);

  const handleSaveClick = async () => {
    setSaving(true);
    try {
      const originalIds = new Set(settings.categories.map(c => c.id));
      const newIdSet = new Set(form.categories.map(c => c.id));

      await Promise.all([
        updateUserSettings({
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
        }),
        ...settings.categories
          .filter(c => !newIdSet.has(c.id))
          .map(c => deleteCategory(boardId, c.id)),
      ]);

      await Promise.all(
        form.categories
          .filter(c => {
            const orig = settings.categories.find(o => o.id === c.id);
            return orig && (orig.label !== c.label || orig.swatchId !== c.swatchId);
          })
          .map(c => updateCategory(boardId, c.id, { label: c.label, swatchId: c.swatchId }))
      );

      const toCreate = form.categories.filter(c => !originalIds.has(c.id));
      const created = await Promise.all(
        toCreate.map(c => createCategory(boardId, { label: c.label, swatchId: c.swatchId }))
      );

      let createIdx = 0;
      const finalCategories = form.categories.map(c =>
        !originalIds.has(c.id) ? created[createIdx++] : c
      );

      // Tags are born from tasks, so the manager only renames/recolors/deletes — no create path.
      const draftTagIds = new Set(tagDraft.map(t => t.id));
      const tagDeletes = tags.filter(t => !draftTagIds.has(t.id));
      const tagUpdates = tagDraft.filter(t => {
        const orig = tags.find(o => o.id === t.id);
        return orig && t.label.trim() && (orig.label !== t.label.trim() || orig.colorId !== t.colorId);
      });
      const tagsTouched = tagDeletes.length > 0 || tagUpdates.length > 0;
      await Promise.all([
        ...tagDeletes.map(t => deleteTag(boardId, t.id)),
        ...tagUpdates.map(t => updateTag(boardId, t.id, { label: t.label.trim(), colorId: t.colorId })),
      ]);

      onSave({ ...form, categories: finalCategories });
      // A tag rename/recolor fans out to tasks (they embed the label/colour), so refetch both.
      if (tagsTouched) onTagsChanged();
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
      className={`modal-overlay${open ? ' modal-overlay--open' : ''}`}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label="Settings">
        <div className="modal__header">
          <span className="modal__title">Settings</span>
          <button className="drawer__close" onClick={onClose} aria-label="Close settings">×</button>
        </div>

        <Tabs
          tabs={SETTINGS_TABS}
          activeTab={activeTab}
          onChange={id => setActiveTab(id as SettingsTab)}
        />

        <div className="modal__body">
          {activeTab === 'general' && (
            <>
              <div className="field">
                <label className="field__label">Display name</label>
                <input
                  className="field__input"
                  value={form.displayName}
                  onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                  placeholder="Your name"
                />
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-email">Email address</label>
                {settings.emailVerified && settings.email && settings.email === emailInput.trim() ? (
                  <p className="settings-hint settings-hint--verified">
                    {settings.email} — verified
                  </p>
                ) : (
                  <p className="settings-hint">
                    Used to send calendar invites for planned tasks.
                    {settings.email && !settings.emailVerified && ' Not yet verified.'}
                  </p>
                )}
                <div className="settings-email-row">
                  <input
                    id="settings-email"
                    className="field__input"
                    type="email"
                    value={emailInput}
                    onChange={e => { setEmailInput(e.target.value); setVerificationSent(false); }}
                    placeholder="you@example.com"
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
                    {sendingVerification ? 'Sending…' : verificationSent ? 'Sent!' : 'Send verification'}
                  </button>
                </div>
                {verificationSent && (
                  <p className="settings-hint">Check your inbox and click the link to verify.</p>
                )}
              </div>

              <ConnectedAccounts />

              <div className="field">
                <label className="field__label" htmlFor="settings-auto-archive">
                  Auto-archive done tasks
                  <HelpTip text="Done tasks are archived after this many days. Leave blank to disable." />
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
                  placeholder="7"
                />
              </div>

              <div className="field">
                <label className="field__label">Notifications</label>
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={form.calendarInviteEmail}
                    onChange={e => setForm(f => ({ ...f, calendarInviteEmail: e.target.checked }))}
                    disabled={!settings.emailVerified}
                  />
                  <span>
                    Email calendar invites for tasks planned to the week
                    {!settings.emailVerified && (
                      <span className="settings-hint"> (verify your email first)</span>
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
                    In-app reminders before a planned task starts
                    <span className="settings-hint"> (sent over Telegram, if connected)</span>
                  </span>
                </label>
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-tz">Time zone</label>
                <input
                  id="settings-tz"
                  className="field__input"
                  list="settings-tz-list"
                  value={form.timeZone}
                  onChange={e => setForm(f => ({ ...f, timeZone: e.target.value }))}
                  placeholder="e.g. Europe/London"
                  autoComplete="off"
                />
                {options && (
                  <datalist id="settings-tz-list">
                    {options.timeZones.map(tz => <option key={tz} value={tz} />)}
                  </datalist>
                )}
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-lang">Language</label>
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
                  First day of the week
                  <HelpTip text="Frames &quot;this week&quot; during your planning sessions." />
                </label>
                <select
                  id="settings-week-start"
                  className="field__input"
                  value={form.weekStartDay ?? ''}
                  onChange={e => setForm(f => ({ ...f, weekStartDay: e.target.value || null }))}
                >
                  <option value="">Not set</option>
                  {DAYS_OF_WEEK.map(d => (
                    <option key={d.value} value={d.value}>{d.label}</option>
                  ))}
                </select>
              </div>

              <div className="danger-zone">
                <p className="danger-zone__label">Danger zone</p>
                <div className="danger-zone__actions">
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleExport}
                    disabled={exporting || deleting || importing}
                  >
                    {exporting ? 'Exporting…' : 'Export my data'}
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleImportClick}
                    disabled={exporting || deleting || importing}
                  >
                    {importing ? 'Importing…' : 'Import data'}
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
                      <span className="danger-zone__confirm-text">This will permanently delete your account and all data. There's no undo.</span>
                      <button
                        type="button"
                        className="btn btn--danger-solid"
                        onClick={handleDeleteConfirmed}
                        disabled={deleting}
                      >
                        {deleting ? 'Deleting…' : 'Yes, delete everything'}
                      </button>
                      <button
                        type="button"
                        className="btn btn--ghost"
                        onClick={() => setDeleteConfirm(false)}
                        disabled={deleting}
                      >
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="btn btn--danger"
                      onClick={() => setDeleteConfirm(true)}
                      disabled={saving}
                    >
                      Delete account
                    </button>
                  )}
                </div>
              </div>
            </>
          )}

          {activeTab === 'categories' && (
            <>
              <div className="field">
                <label className="field__label">Categories</label>
                <p className="settings-hint">Post-it color on the board. Every task belongs to one.</p>
                <CategoryEditor
                  categories={form.categories}
                  usage={usage}
                  onChange={next => setForm(f => ({ ...f, categories: next }))}
                />
              </div>

              <div className="field">
                <label className="field__label">Tags</label>
                <p className="settings-hint">Washi-tape labels a task can carry several of. Rename, recolor, or remove them.</p>
                <TagEditor tags={tagDraft} onChange={setTagDraft} />
              </div>
            </>
          )}

          {activeTab === 'assistant' && (
            <>
              <div className="field">
                <label className="field__label">
                  AI access
                  <HelpTip
                    side="left"
                    text="Turns off every AI-driven feature for your account: weekly planning, quick-capture suggestions, and the Telegram /plan and /add commands. On a board you share, the board's AI features stop working for everyone while yours is off."
                  />
                </label>
                <Toggle
                  checked={form.aiEnabled}
                  onChange={next => setForm(f => ({ ...f, aiEnabled: next }))}
                  label="Allow AI features to read my data"
                />
              </div>

              <div className="field">
                <label className="field__label">
                  AI plan
                  <HelpTip side="left" text="Your token budget for AI-driven features over a rolling 30-day window." />
                </label>
                <AiUsageMeter />
              </div>

              <fieldset className={styles.aiFieldset} disabled={!form.aiEnabled}>
              <div className="field">
                <label className="field__label">
                  Weekly planning session
                  <HelpTip text="We'll start your planning conversation via Telegram at this time each week." />
                </label>
                <Toggle
                  checked={planningEnabled}
                  onChange={next => setForm(f => ({
                    ...f,
                    planningCron: next ? composeCron('MON', planningParts.time) : null,
                  }))}
                  label="Start a weekly planning session"
                />
                {planningEnabled && (
                  <div className={styles.planningRow}>
                    <select
                      aria-label="Planning day"
                      className="field__input"
                      value={planningParts.day}
                      onChange={e => setForm(f => ({ ...f, planningCron: composeCron(e.target.value, planningParts.time) }))}
                    >
                      {DAYS_OF_WEEK.map(d => (
                        <option key={d.value} value={d.cron}>{d.label}</option>
                      ))}
                    </select>
                    <input
                      aria-label="Planning time"
                      type="time"
                      className="field__input"
                      value={planningParts.time}
                      onChange={e => setForm(f => ({ ...f, planningCron: composeCron(planningParts.day, e.target.value) }))}
                    />
                  </div>
                )}
              </div>

              <div className="field">
                <label className="field__label">
                  Reminders
                  <HelpTip text="When on, the reminder sent before a planned task starts is written by the assistant — a short, personalized nudge instead of the standard template. The reminder buttons (snooze, mark done) work either way." />
                </label>
                <Toggle
                  checked={form.aiEnhancedReminders}
                  onChange={next => setForm(f => ({ ...f, aiEnhancedReminders: next }))}
                  label="Let the assistant write my task reminders"
                />
              </div>

              <div className="field">
                <label className="field__label" htmlFor="settings-gender">
                  How should the assistant address you?
                  <HelpTip text="Sets pronouns and gendered language used during planning conversations." />
                </label>
                <select
                  id="settings-gender"
                  className="field__select"
                  value={form.gender ?? ''}
                  onChange={e => setForm(f => ({ ...f, gender: e.target.value || undefined }))}
                >
                  <option value="">— no preference</option>
                  {genderOptions.map(opt => (
                    <option key={opt.code} value={opt.code}>{opt.label}</option>
                  ))}
                </select>
              </div>

              <div className="field">
                <label className="field__label">
                  Personal context
                  <HelpTip text="Facts the AI planner will use when scheduling your week — preferences, recurring commitments, energy patterns." />
                </label>
                <textarea
                  className="field__textarea"
                  value={form.contextBlock}
                  onChange={e => setForm(f => ({ ...f, contextBlock: e.target.value }))}
                  rows={6}
                  placeholder="e.g. I prefer deep work in the morning…"
                />
              </div>
              </fieldset>
            </>
          )}
        </div>

        <div className="modal__footer">
          <button className="btn btn--ghost" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn--primary" onClick={handleSaveClick} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
      <ImportResultDialog result={importResult} onClose={handleImportResultClose} />
    </div>
  );
}

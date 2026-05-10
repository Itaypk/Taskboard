import { useState, useEffect, useMemo } from 'react';
import type { UserSettings, Task, SettingsOptions } from '../types';
import { CategoryEditor } from './CategoryEditor';
import { Tabs } from './Tabs';
import { createCategory, updateCategory, deleteCategory, updateUserSettings, fetchSettingsOptions, deleteAccount, exportAccount, requestEmailVerification } from '../api';

interface SettingsModalProps {
  settings: UserSettings;
  tasks: Task[];
  open: boolean;
  onClose: () => void;
  onSave: (s: UserSettings) => void;
  onAccountDeleted: () => void;
}

const SETTINGS_TABS = [
  { id: 'general', label: 'General' },
  { id: 'categories', label: 'Categories' },
  { id: 'assistant', label: 'Assistant' },
];

export function SettingsModal({ settings, tasks, open, onClose, onSave, onAccountDeleted }: SettingsModalProps) {
  const [form, setForm] = useState<UserSettings>(settings);
  const [activeTab, setActiveTab] = useState<'general' | 'categories' | 'assistant'>('general');
  const [wasOpen, setWasOpen] = useState(open);
  const [saving, setSaving] = useState(false);
  const [options, setOptions] = useState<SettingsOptions | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [emailInput, setEmailInput] = useState(settings.email ?? '');
  const [verificationSent, setVerificationSent] = useState(false);
  const [sendingVerification, setSendingVerification] = useState(false);

  if (open && !wasOpen) {
    setWasOpen(true);
    setForm(settings);
    setEmailInput(settings.email ?? '');
    setDeleteConfirm(false);
    setVerificationSent(false);
    setActiveTab('general');
  } else if (!open && wasOpen) {
    setWasOpen(false);
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
          gender: form.gender,
          agentDescription: form.agentDescription,
        }),
        ...settings.categories
          .filter(c => !newIdSet.has(c.id))
          .map(c => deleteCategory(c.id)),
      ]);

      await Promise.all(
        form.categories
          .filter(c => {
            const orig = settings.categories.find(o => o.id === c.id);
            return orig && (orig.label !== c.label || orig.swatchId !== c.swatchId);
          })
          .map(c => updateCategory(c.id, { label: c.label, swatchId: c.swatchId }))
      );

      const toCreate = form.categories.filter(c => !originalIds.has(c.id));
      const created = await Promise.all(
        toCreate.map(c => createCategory({ label: c.label, swatchId: c.swatchId }))
      );

      let createIdx = 0;
      const finalCategories = form.categories.map(c =>
        !originalIds.has(c.id) ? created[createIdx++] : c
      );

      onSave({ ...form, categories: finalCategories });
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
          onChange={id => setActiveTab(id as 'general' | 'categories' | 'assistant')}
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

              <div className="danger-zone">
                <p className="danger-zone__label">Danger zone</p>
                <div className="danger-zone__actions">
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleExport}
                    disabled={exporting || deleting}
                  >
                    {exporting ? 'Exporting…' : 'Export my data'}
                  </button>
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
            <div className="field">
              <p className="settings-hint">Post-it color on the board.</p>
              <CategoryEditor
                categories={form.categories}
                usage={usage}
                onChange={next => setForm(f => ({ ...f, categories: next }))}
              />
            </div>
          )}

          {activeTab === 'assistant' && (
            <>
              <div className="field">
                <label className="field__label" htmlFor="settings-gender">How should the assistant address you?</label>
                <p className="settings-hint">Sets pronouns and gendered language used during planning conversations.</p>
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
                <label className="field__label">Personal context</label>
                <p className="settings-hint">
                  Facts the AI planner will use when scheduling your week — preferences, recurring commitments, energy patterns.
                </p>
                <textarea
                  className="field__textarea"
                  value={form.contextBlock}
                  onChange={e => setForm(f => ({ ...f, contextBlock: e.target.value }))}
                  rows={6}
                  placeholder="e.g. I prefer deep work in the morning…"
                />
              </div>
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
    </div>
  );
}

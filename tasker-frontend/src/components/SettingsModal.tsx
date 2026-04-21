import { useState, useEffect, useMemo } from 'react';
import type { UserSettings, Task } from '../types';
import { CategoryEditor } from './CategoryEditor';
import { createCategory, updateCategory, deleteCategory } from '../api';

interface SettingsModalProps {
  settings: UserSettings;
  tasks: Task[];
  open: boolean;
  onClose: () => void;
  onSave: (s: UserSettings) => void;
}

export function SettingsModal({ settings, tasks, open, onClose, onSave }: SettingsModalProps) {
  const [form, setForm] = useState<UserSettings>(settings);
  const [wasOpen, setWasOpen] = useState(open);
  const [saving, setSaving] = useState(false);

  if (open && !wasOpen) {
    setWasOpen(true);
    setForm(settings);
  } else if (!open && wasOpen) {
    setWasOpen(false);
  }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

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

      await Promise.all(
        settings.categories
          .filter(c => !newIdSet.has(c.id))
          .map(c => deleteCategory(c.id))
      );

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
        <div className="modal__body">
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
            <label className="field__label">Categories</label>
            <p className="settings-hint">Post-it color on the board.</p>
            <CategoryEditor
              categories={form.categories}
              usage={usage}
              onChange={next => setForm(f => ({ ...f, categories: next }))}
            />
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

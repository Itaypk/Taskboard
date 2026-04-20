import { useState, useEffect, useMemo } from 'react';
import type { UserSettings, Task } from '../types';
import { CategoryEditor } from './CategoryEditor';

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
          <button className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn--primary" onClick={() => { onSave(form); onClose(); }}>
            Save
          </button>
        </div>
      </div>
    </div>
  );
}

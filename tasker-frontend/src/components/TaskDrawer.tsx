import { useState, useEffect } from 'react';
import type { Task, Tag, TagColorId, Category } from '../types';
import { TAG_PALETTE, PAPER_SWATCHES } from '../types';
import { WashiTape } from './WashiTape';
import { Autocomplete } from './Autocomplete';
import { generateId, formatRelative } from '../utils';

interface TaskDrawerProps {
  task: Task | null;
  isNew: boolean;
  open: boolean;
  categories: Category[];
  availableTags: Tag[];
  defaultCategoryId: string | null;
  onClose: () => void;
  onSave: (task: Omit<Task, 'sortKey'>) => void;
  onDelete: (id: string) => void;
  onMarkDone: (id: string) => void;
  onMarkTodo: (id: string) => void;
}

type FormState = Omit<Task, 'id' | 'createdAt' | 'sortKey'>;

function makeEmpty(defaultCategoryId: string | null): FormState {
  return {
    title: '',
    description: '',
    url: '',
    priority: undefined,
    deadline: '',
    relevantFrom: '',
    estimatedMinutes: undefined,
    status: 'todo',
    categoryId: defaultCategoryId ?? '',
    tags: [],
  };
}

export function TaskDrawer({
  task, isNew, open, categories, availableTags, defaultCategoryId,
  onClose, onSave, onDelete, onMarkDone, onMarkTodo,
}: TaskDrawerProps) {
  const [form, setForm] = useState<FormState>(makeEmpty(defaultCategoryId));
  const [showTagForm, setShowTagForm] = useState(false);
  const [tagLabel, setTagLabel] = useState('');
  const [tagColorId, setTagColorId] = useState<TagColorId>('sage');
  const [tagId, setTagId] = useState<string | null>(null);
  const [formKey, setFormKey] = useState<string | null>(null);

  const targetKey = open ? (task?.id ?? '__new__') : null;
  if (targetKey !== formKey) {
    setFormKey(targetKey);
    if (targetKey !== null) {
      setForm(task ? {
        title: task.title,
        description: task.description ?? '',
        url: task.url ?? '',
        priority: task.priority,
        deadline: task.deadline ?? '',
        relevantFrom: task.relevantFrom ?? '',
        estimatedMinutes: task.estimatedMinutes,
        status: task.status,
        categoryId: task.categoryId,
        tags: [...task.tags],
      } : makeEmpty(defaultCategoryId));
      setShowTagForm(false);
      setTagLabel('');
      setTagId(null);
    }
  }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const selectedCategory = categories.find(c => c.id === form.categoryId);
  const selectedSwatch = selectedCategory
    ? PAPER_SWATCHES.find(s => s.id === selectedCategory.swatchId)
    : undefined;

  const handleSave = () => {
    if (!form.title.trim() || !form.categoryId) return;
    const now = new Date().toISOString();
    onSave({
      id: task?.id ?? generateId(),
      createdAt: task?.createdAt ?? now,
      ...form,
      title: form.title.trim(),
      description: form.description?.trim() || undefined,
      url: form.url?.trim() || undefined,
      deadline: form.deadline || undefined,
      relevantFrom: form.relevantFrom || undefined,
      estimatedMinutes: form.estimatedMinutes || undefined,
    });
  };

  const commitTag = () => {
    if (!tagLabel.trim() || form.tags.length >= 3) return;
    const existing = availableTags.find(t => t.label.toLowerCase() === tagLabel.trim().toLowerCase());
    const newTag: Tag = { 
      id: tagId || existing?.id || '', 
      label: existing?.label || tagLabel.trim(), 
      colorId: existing?.colorId || tagColorId 
    };
    setForm(f => ({ ...f, tags: [...f.tags, newTag] }));
    setTagLabel('');
    setTagColorId('sage');
    setTagId(null);
    setShowTagForm(false);
  };

  const removeTag = (i: number) =>
    setForm(f => ({ ...f, tags: f.tags.filter((_, idx) => idx !== i) }));

  const accentStyle = selectedSwatch
    ? {
        ['--accent-paper' as string]: selectedSwatch.paper,
        ['--accent-edge' as string]: selectedSwatch.edge,
        ['--accent-ink' as string]: selectedSwatch.ink,
      }
    : {};

  return (
    <>
      <div className={`overlay${open ? ' overlay--open' : ''}`} onClick={onClose} />
      <aside
        className={`drawer${open ? ' drawer--open' : ''}`}
        aria-hidden={!open}
        role="dialog"
        aria-modal="true"
        aria-label={isNew ? 'New task' : 'Task details'}
        style={accentStyle}
      >
        <div className="drawer__header">
          <span className="drawer__label">{isNew ? 'New task' : 'Task'}</span>
          <button className="drawer__close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="drawer__body">
          <input
            className="field__title-input"
            value={form.title}
            onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
            placeholder="Task title…"
            autoFocus={isNew}
          />

          <div className="field">
            <label className="field__label">Category</label>
            <div className="cat-picker" role="radiogroup" aria-label="Category">
              {categories.map(cat => {
                const sw = PAPER_SWATCHES.find(s => s.id === cat.swatchId) ?? PAPER_SWATCHES[6];
                const selected = cat.id === form.categoryId;
                return (
                  <button
                    key={cat.id}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    className={`cat-pick${selected ? ' cat-pick--selected' : ''}`}
                    onClick={() => setForm(f => ({ ...f, categoryId: cat.id }))}
                  >
                    <span
                      className="cat-pick__paper"
                      style={{ background: sw.paper, borderColor: sw.edge, color: sw.ink }}
                    />
                    <span className="cat-pick__label">{cat.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="field">
            <label className="field__label">Priority</label>
            <select
              className="field__select"
              value={form.priority ?? ''}
              onChange={e => setForm(f => ({ ...f, priority: (e.target.value as Task['priority']) || undefined }))}
            >
              <option value="">None</option>
              <option value="high">High</option>
              <option value="medium">Medium</option>
              <option value="low">Low</option>
            </select>
          </div>

          <div className="field">
            <label className="field__label">Description</label>
            <textarea
              className="field__textarea"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              placeholder="Optional notes, context…"
              rows={3}
            />
          </div>

          <div className="field">
            <label className="field__label">Link</label>
            <input
              className="field__input"
              type="url"
              value={form.url}
              onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
              placeholder="https://…"
            />
          </div>

          <div className="row-2">
            <div className="field">
              <label className="field__label">Deadline</label>
              <input
                className="field__input"
                type="date"
                value={form.deadline}
                onChange={e => setForm(f => ({ ...f, deadline: e.target.value }))}
              />
            </div>
            <div className="field">
              <label className="field__label">Available from</label>
              <input
                className="field__input"
                type="date"
                value={form.relevantFrom}
                onChange={e => setForm(f => ({ ...f, relevantFrom: e.target.value }))}
              />
            </div>
          </div>

          <div className="field">
            <label className="field__label">Est. minutes</label>
            <input
              className="field__input"
              type="number"
              min={0}
              step={15}
              value={form.estimatedMinutes ?? ''}
              onChange={e => setForm(f => ({
                ...f,
                estimatedMinutes: e.target.value ? Number(e.target.value) : undefined,
              }))}
              placeholder="90"
            />
          </div>

          <div className="field">
            <label className="field__label">
              Tags{' '}
              <span className="field__label-hint">({form.tags.length}/3)</span>
            </label>
            <div className="tape-editor">
              <div className="tape-editor__preview">
                {form.tags.map((tag, i) => (
                  <WashiTape
                    key={i}
                    tag={tag}
                    index={i}
                    idSeed={'draft-' + tag.label + i}
                    onRemove={() => removeTag(i)}
                  />
                ))}
                {form.tags.length === 0 && !showTagForm && (
                  <span className="tape-editor__hint">No tags yet</span>
                )}
              </div>

              {form.tags.length < 3 && !showTagForm && (
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => setShowTagForm(true)}
                >
                  + Add tag
                </button>
              )}

              {showTagForm && (() => {
                const isExisting = tagId !== null || availableTags.some(t => t.label.toLowerCase() === tagLabel.trim().toLowerCase());
                return (
                <div className="tag-form">
                  <Autocomplete
                    value={tagLabel}
                    onChange={(val) => {
                      setTagLabel(val);
                      setTagId(null);
                      const existing = availableTags.find(t => t.label.toLowerCase() === val.trim().toLowerCase());
                      if (existing) setTagColorId(existing.colorId);
                    }}
                    onSelect={(opt) => {
                      setTagId(opt.id);
                      setTagLabel(opt.label);
                      if (opt.colorId) setTagColorId(opt.colorId as TagColorId);
                    }}
                    onKeyDown={e => {
                      if (e.key === 'Enter') { e.preventDefault(); commitTag(); }
                      if (e.key === 'Escape') setShowTagForm(false);
                    }}
                    options={availableTags
                      .filter(t => !form.tags.some(ft => ft.id === t.id || ft.label.toLowerCase() === t.label.toLowerCase()))
                      .map(t => ({
                        id: t.id,
                        label: t.label,
                        colorId: t.colorId,
                        color: TAG_PALETTE.find(c => c.id === t.colorId)?.text || 'currentColor'
                      }))}
                    placeholder="Tag label…"
                    autoFocus
                  />
                  {!isExisting && (
                    <div className="color-swatches">
                      {TAG_PALETTE.map(c => (
                        <button
                          key={c.id}
                          type="button"
                          className={`color-swatch${tagColorId === c.id ? ' color-swatch--selected' : ''}`}
                          style={{ background: c.text }}
                          onClick={() => setTagColorId(c.id as TagColorId)}
                          aria-label={c.id}
                        />
                      ))}
                    </div>
                  )}
                  <div className="tag-form__actions">
                    <button
                      type="button"
                      className="btn btn--primary btn--sm"
                      onClick={commitTag}
                      disabled={!tagLabel.trim()}
                    >
                      Add
                    </button>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() => setShowTagForm(false)}
                    >
                      Cancel
                    </button>
                  </div>
                </div>
                );
              })()}
            </div>
          </div>
        </div>

        {!isNew && task && (
          <div className="drawer__timestamps">
            <span>Created {formatRelative(task.createdAt)}</span>
            {task.updatedAt && <span>· Updated {formatRelative(task.updatedAt)}</span>}
          </div>
        )}

        <div className="drawer__footer">
          <div className="drawer__footer-left">
            {!isNew && (
              <>
                {task?.status === 'archived' ? (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => { onMarkTodo(task!.id); onClose(); }}
                  >
                    ↺ Unarchive
                  </button>
                ) : task?.status === 'done' ? (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => { onMarkTodo(task!.id); onClose(); }}
                  >
                    ↺ Mark to-do
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => { onMarkDone(task!.id); onClose(); }}
                  >
                    Mark done
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn--danger btn--sm"
                  onClick={() => { onDelete(task!.id); onClose(); }}
                >
                  Delete
                </button>
              </>
            )}
          </div>
          <div className="drawer__footer-right">
            <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
            <button
              type="button"
              className="btn btn--primary"
              onClick={handleSave}
              disabled={!form.title.trim() || !form.categoryId}
            >
              {isNew ? 'Pin it up' : 'Save'}
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}

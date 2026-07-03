import { useState, useEffect, Suspense, lazy } from 'react';
import type { Task, Tag, TagColorId, Category } from '../types';
import { TAG_PALETTE, PAPER_SWATCHES } from '../types';
import { ApiError, type BoardMember } from '../api';
import { WashiTape } from './WashiTape';
import { TagEditModal } from './TagEditModal';
import { Autocomplete } from './Autocomplete';
import { generateId, formatRelative } from '../utils';
import { resolveTaskLink, linkLabel } from '../taskLink';

// Lazy so the TipTap/ProseMirror bundle only loads when a drawer is actually opened, keeping
// it out of the initial app chunk (the drawer is always mounted, just hidden via CSS).
const NoteEditor = lazy(() => import('./NoteEditor'));

interface TaskDrawerProps {
  task: Task | null;
  isNew: boolean;
  open: boolean;
  categories: Category[];
  availableTags: Tag[];
  defaultCategoryId: string | null;
  /** Board members for the assignee picker; only non-empty on shared (>1 member) boards. */
  members: BoardMember[];
  currentUserId: string | null;
  onClose: () => void;
  onSave: (task: Omit<Task, 'sortKey'>) => void | Promise<void>;
  onDelete: (id: string) => void;
  onMarkDone: (id: string) => void;
  onMarkTodo: (id: string) => void;
  onSetAssignee: (id: string, userId: string | null) => void;
  /** Persists a board-wide tag rename/recolor (from the inline tag-edit modal) and refreshes tags/tasks. */
  onUpdateTag?: (tagId: string, label: string, colorId: TagColorId) => void;
  /** Follows a (read-only) task's link field — internal routes/actions can't be plain anchors. */
  onFollowLink?: (task: Task) => void;
}

type FormState = Omit<Task, 'id' | 'createdAt' | 'sortKey'>;
type FieldErrors = Partial<Record<'title' | 'url' | 'description', string>>;

// Mirrors the server-side constraints on CreateBacklogTaskRequest so the user gets an inline,
// field-specific reason before submitting (rather than a generic "invalid request" toast).
const URL_PATTERN = /^https?:\/\//i;

function validate(form: FormState): FieldErrors {
  const errors: FieldErrors = {};
  const title = form.title.trim();
  if (!title) errors.title = 'Title is required.';
  else if (title.length > 500) errors.title = 'Title must be at most 500 characters.';
  if (form.description && form.description.length > 5000) {
    errors.description = 'Description must be at most 5000 characters.';
  }
  const url = form.url?.trim();
  if (url) {
    if (!URL_PATTERN.test(url)) errors.url = 'Link must start with http:// or https://';
    else if (url.length > 2000) errors.url = 'Link must be at most 2000 characters.';
  }
  return errors;
}

// Map a failed save's server-side field errors onto the inline slots we render. The server field
// names (from the request DTO) match our keys; fields without an inline slot fall through to the
// global error toast that api.ts already raised.
function serverFieldErrors(e: unknown): FieldErrors | null {
  if (!(e instanceof ApiError) || !e.fieldErrors?.length) return null;
  const known: (keyof FieldErrors)[] = ['title', 'url', 'description'];
  const mapped: FieldErrors = {};
  for (const fe of e.fieldErrors) {
    const key = known.find(k => k === fe.field);
    if (key && !mapped[key]) mapped[key] = fe.message;
  }
  return Object.keys(mapped).length > 0 ? mapped : null;
}

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
  task, isNew, open, categories, availableTags, defaultCategoryId, members, currentUserId,
  onClose, onSave, onDelete, onMarkDone, onMarkTodo, onSetAssignee, onUpdateTag, onFollowLink,
}: TaskDrawerProps) {
  const [form, setForm] = useState<FormState>(makeEmpty(defaultCategoryId));
  const [showTagForm, setShowTagForm] = useState(false);
  const [tagLabel, setTagLabel] = useState('');
  const [tagColorId, setTagColorId] = useState<TagColorId>('sage');
  const [tagId, setTagId] = useState<string | null>(null);
  const [editingTagIndex, setEditingTagIndex] = useState<number | null>(null);
  const [formKey, setFormKey] = useState<string | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);

  const targetKey = open ? (task?.id ?? '__new__') : null;
  if (targetKey !== formKey) {
    setFormKey(targetKey);
    setErrors({});
    setFormError(null);
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
      setEditingTagIndex(null);
    }
  }

  useEffect(() => {
    // The tag-edit modal owns Escape while it's open, so the drawer doesn't close out from under it.
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape' && editingTagIndex === null) onClose(); };
    if (open) window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose, editingTagIndex]);

  // Tutorial tasks are immutable — a UX guardrail (not a server-side rule). You can still complete
  // or delete them; only field editing is locked.
  const readOnly = !isNew && !!task?.tutorial;
  // For read-only tasks we surface the link as a real, clickable affordance instead of a dead input.
  const taskLink = !isNew && task ? resolveTaskLink(task.url) : null;

  const selectedCategory = categories.find(c => c.id === form.categoryId);
  const selectedSwatch = selectedCategory
    ? PAPER_SWATCHES.find(s => s.id === selectedCategory.swatchId)
    : undefined;

  const clearError = (field: keyof FieldErrors) =>
    setErrors(e => (e[field] ? { ...e, [field]: undefined } : e));

  const handleSave = async () => {
    if (!form.categoryId) return;
    setFormError(null);
    const found = validate(form);
    if (found.title || found.url || found.description) {
      setErrors(found);
      return;
    }
    const now = new Date().toISOString();
    try {
      await onSave({
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
    } catch (e) {
      // The drawer owns error display here (the save call opts out of the global toast). Server-side
      // field reasons go inline; anything else shows as a form-level banner. The drawer stays open.
      const mapped = serverFieldErrors(e);
      if (mapped) {
        setErrors(mapped);
      } else {
        setFormError(e instanceof ApiError ? e.userMessage : 'Something went wrong. Please try again.');
      }
    }
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

  // One-click add of an existing board tag (no typing). No-op if already applied or at the cap.
  const addExistingTag = (tag: Tag) => {
    if (form.tags.length >= 3) return;
    if (form.tags.some(t => t.id === tag.id || t.label.toLowerCase() === tag.label.toLowerCase())) return;
    setForm(f => ({ ...f, tags: [...f.tags, { id: tag.id, label: tag.label, colorId: tag.colorId }] }));
  };

  // Most-used board tags not already on this task (availableTags arrives popularity-ordered from the API).
  const tagSuggestions = availableTags
    .filter(t => !form.tags.some(ft => ft.id === t.id || ft.label.toLowerCase() === t.label.toLowerCase()))
    .slice(0, 6);

  // Tapes loaded from a saved task carry no id (the task API embeds only label+colour), so resolve the
  // board tag id by label. Returns undefined for a freshly-typed tag that hasn't been persisted yet.
  const resolveBoardTagId = (tag: Tag): string | undefined =>
    tag.id || availableTags.find(t => t.label.toLowerCase() === tag.label.toLowerCase())?.id;

  const editingTag = editingTagIndex !== null ? form.tags[editingTagIndex] : null;

  const handleTagEditSave = (label: string, colorId: TagColorId) => {
    if (editingTagIndex === null) return;
    const current = form.tags[editingTagIndex];
    setForm(f => ({
      ...f,
      tags: f.tags.map((t, i) => i === editingTagIndex ? { ...t, label, colorId } : t),
    }));
    const boardTagId = resolveBoardTagId(current);
    if (boardTagId && (current.label !== label || current.colorId !== colorId)) {
      onUpdateTag?.(boardTagId, label, colorId);
    }
    setEditingTagIndex(null);
  };

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
          {formError && (
            <div className="drawer__error" role="alert">{formError}</div>
          )}
          {readOnly && (
            <p className="drawer__readonly-hint">This is a tutorial task. Mark it done or clear the tutorial when you’re ready.</p>
          )}
          <fieldset className="drawer__fieldset" disabled={readOnly}>
          <input
            className={`field__title-input${errors.title ? ' field__input--error' : ''}`}
            value={form.title}
            onChange={e => { setForm(f => ({ ...f, title: e.target.value })); clearError('title'); }}
            placeholder="Task title…"
            autoFocus={isNew}
            aria-invalid={errors.title ? true : undefined}
          />
          {errors.title && <p className="field__error">{errors.title}</p>}

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

          {!isNew && task && members.length > 1 && (
            <div className="field">
              <label className="field__label">Assignee</label>
              <select
                className="field__select"
                value={task.assigneeUserId ?? ''}
                onChange={e => onSetAssignee(task.id, e.target.value || null)}
              >
                <option value="">Unassigned</option>
                {members.map(m => (
                  <option key={m.userId} value={m.userId}>
                    {m.userId === currentUserId ? `${m.displayName} (you)` : m.displayName}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="field">
            <label className="field__label">Description</label>
            {open ? (
              <Suspense fallback={<div className="field__textarea" aria-busy="true" style={{ minHeight: 96 }} />}>
                <NoteEditor
                  value={form.description ?? ''}
                  onChange={md => { setForm(f => ({ ...f, description: md })); clearError('description'); }}
                  placeholder="Optional notes, context…"
                  error={!!errors.description}
                  disabled={readOnly}
                />
              </Suspense>
            ) : (
              <div className="field__textarea" style={{ minHeight: 96 }} />
            )}
            {errors.description && <p className="field__error">{errors.description}</p>}
          </div>

          {(!readOnly || taskLink) && (
            <div className="field">
              <label className="field__label">Link</label>
              {readOnly && taskLink ? (
                // Internal/action links can't be plain anchors; let Board resolve them. An <a> stays
                // clickable inside the disabled fieldset (only form controls are disabled).
                <a
                  className="drawer__link"
                  href={taskLink.kind === 'external' ? taskLink.href : undefined}
                  target={taskLink.kind === 'external' ? '_blank' : undefined}
                  rel={taskLink.kind === 'external' ? 'noopener noreferrer' : undefined}
                  onClick={e => { e.preventDefault(); onFollowLink?.(task!); }}
                  title={taskLink.kind === 'external' ? taskLink.href : undefined}
                >
                  {linkLabel(taskLink)} ↗
                </a>
              ) : (
                <>
                  <input
                    className={`field__input${errors.url ? ' field__input--error' : ''}`}
                    type="url"
                    value={form.url}
                    onChange={e => { setForm(f => ({ ...f, url: e.target.value })); clearError('url'); }}
                    placeholder="https://…"
                    aria-invalid={errors.url ? true : undefined}
                  />
                  {errors.url && <p className="field__error">{errors.url}</p>}
                </>
              )}
            </div>
          )}

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
                    onClick={readOnly ? undefined : () => setEditingTagIndex(i)}
                  />
                ))}
                {form.tags.length === 0 && !showTagForm && (
                  <span className="tape-editor__hint">No tags yet</span>
                )}
              </div>

              {form.tags.length < 3 && !showTagForm && tagSuggestions.length > 0 && (
                <div className="tape-quickadd">
                  {tagSuggestions.map(t => (
                    <button
                      key={t.id}
                      type="button"
                      className="tape-quickadd__chip"
                      onClick={() => addExistingTag(t)}
                      aria-label={`Add tag ${t.label}`}
                      title={`Add ${t.label}`}
                    >
                      <WashiTape tag={t} idSeed={'sugg-' + t.id} />
                    </button>
                  ))}
                </div>
              )}

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
          </fieldset>
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
            <button type="button" className="btn btn--ghost" onClick={onClose}>{readOnly ? 'Close' : 'Cancel'}</button>
            {!readOnly && (
              <button
                type="button"
                className="btn btn--primary"
                onClick={handleSave}
                disabled={!form.title.trim() || !form.categoryId}
              >
                {isNew ? 'Pin it up' : 'Save'}
              </button>
            )}
          </div>
        </div>
      </aside>

      <TagEditModal
        open={editingTag !== null}
        initialLabel={editingTag?.label ?? ''}
        initialColorId={editingTag?.colorId ?? 'sage'}
        persists={editingTag ? resolveBoardTagId(editingTag) !== undefined : false}
        onSave={handleTagEditSave}
        onClose={() => setEditingTagIndex(null)}
      />
    </>
  );
}

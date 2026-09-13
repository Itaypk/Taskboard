import { useState, useEffect, Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import type { Task, Tag, TagColorId, Category } from '../types';
import { TAG_PALETTE, PAPER_SWATCHES } from '../types';
import { ApiError, type BoardMember } from '../api';
import { WashiTape } from './WashiTape';
import { TagEditModal } from './TagEditModal';
import { Autocomplete } from './Autocomplete';
import { Toggle } from './Toggle';
import { HelpTip } from './HelpTip';
import { RecurrenceFields } from './RecurrenceFields';
import { generateId, formatRelative } from '../utils';
import { defaultRule, firstOccurrence, formatShortDate, isRuleValid, normalizeRule, todayIso } from '../recurrence';
import { resolveTaskLink, linkLabel } from '../taskLink';
import i18n from '../i18n';

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
  /** Whether the user has the AI assistant enabled; the "hide from assistant" control is pointless (and hidden) when off. */
  aiEnabled: boolean;
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
type FieldErrors = Partial<Record<'title' | 'url' | 'description' | 'recurrence', string>>;

// Mirrors the server-side constraints on CreateBacklogTaskRequest so the user gets an inline,
// field-specific reason before submitting (rather than a generic "invalid request" toast).
const URL_PATTERN = /^https?:\/\//i;

function validate(form: FormState): FieldErrors {
  const errors: FieldErrors = {};
  const title = form.title.trim();
  if (!title) errors.title = i18n.t('taskDrawer.validation.titleRequired');
  else if (title.length > 500) errors.title = i18n.t('taskDrawer.validation.titleTooLong');
  if (form.description && form.description.length > 5000) {
    errors.description = i18n.t('taskDrawer.validation.descriptionTooLong');
  }
  const url = form.url?.trim();
  if (url) {
    if (!URL_PATTERN.test(url)) errors.url = i18n.t('taskDrawer.validation.urlInvalid');
    else if (url.length > 2000) errors.url = i18n.t('taskDrawer.validation.urlTooLong');
  }
  if (form.recurrence && !isRuleValid(form.recurrence)) errors.recurrence = i18n.t('recurrence.invalid');
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
    hiddenFromAssistant: false,
    recurrence: null,
  };
}

export function TaskDrawer({
  task, isNew, open, categories, availableTags, defaultCategoryId, members, currentUserId, aiEnabled,
  onClose, onSave, onDelete, onMarkDone, onMarkTodo, onSetAssignee, onUpdateTag, onFollowLink,
}: TaskDrawerProps) {
  const { t } = useTranslation();
  const [form, setForm] = useState<FormState>(makeEmpty(defaultCategoryId));
  const [showTagForm, setShowTagForm] = useState(false);
  const [tagLabel, setTagLabel] = useState('');
  const [tagColorId, setTagColorId] = useState<TagColorId>('sage');
  const [tagId, setTagId] = useState<string | null>(null);
  const [editingTagIndex, setEditingTagIndex] = useState<number | null>(null);
  const [tagsExpanded, setTagsExpanded] = useState(false);
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
        hiddenFromAssistant: task.hiddenFromAssistant ?? false,
        // The save is a full replace: leaving this out would silently stop the task recurring.
        recurrence: task.recurrence ?? null,
      } : makeEmpty(defaultCategoryId));
      setShowTagForm(false);
      setTagLabel('');
      setTagId(null);
      setEditingTagIndex(null);
      setTagsExpanded(false);
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
    if (found.title || found.url || found.description || found.recurrence) {
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
        recurrence: form.recurrence ? normalizeRule(form.recurrence) : null,
      });
    } catch (e) {
      // The drawer owns error display here (the save call opts out of the global toast). Server-side
      // field reasons go inline; anything else shows as a form-level banner. The drawer stays open.
      const mapped = serverFieldErrors(e);
      if (mapped) {
        setErrors(mapped);
      } else {
        setFormError(e instanceof ApiError ? e.userMessage : t('taskDrawer.saveError'));
      }
    }
  };

  const setRepeats = (on: boolean) => {
    clearError('recurrence');
    setForm(f => {
      if (!on) return { ...f, recurrence: null };
      const today = todayIso();
      const rule = defaultRule('EVERY_N_MONTHS', today);
      return {
        ...f,
        recurrence: rule,
        relevantFrom: f.relevantFrom || firstOccurrence(rule, today),
        // A recurring task is never stored done (completing it is what rolls it forward), so
        // switching repeat on for a done task reopens it.
        status: f.status === 'done' ? 'todo' : f.status,
      };
    });
  };

  const commitTag = () => {
    if (!tagLabel.trim() || form.tags.length >= 3) return;
    const existing = availableTags.find(tag => tag.label.toLowerCase() === tagLabel.trim().toLowerCase());
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
    if (form.tags.some(existing => existing.id === tag.id || existing.label.toLowerCase() === tag.label.toLowerCase())) return;
    setForm(f => ({ ...f, tags: [...f.tags, { id: tag.id, label: tag.label, colorId: tag.colorId }] }));
  };

  // Most-used board tags not already on this task (availableTags arrives popularity-ordered from the API).
  const tagSuggestions = availableTags
    .filter(tag => !form.tags.some(ft => ft.id === tag.id || ft.label.toLowerCase() === tag.label.toLowerCase()))
    .slice(0, 6);

  // Tapes loaded from a saved task carry no id (the task API embeds only label+colour), so resolve the
  // board tag id by label. Returns undefined for a freshly-typed tag that hasn't been persisted yet.
  const resolveBoardTagId = (tag: Tag): string | undefined =>
    tag.id || availableTags.find(existing => existing.label.toLowerCase() === tag.label.toLowerCase())?.id;

  const editingTag = editingTagIndex !== null ? form.tags[editingTagIndex] : null;

  const handleTagEditSave = (label: string, colorId: TagColorId) => {
    if (editingTagIndex === null) return;
    const current = form.tags[editingTagIndex];
    setForm(f => ({
      ...f,
      tags: f.tags.map((tag, i) => i === editingTagIndex ? { ...tag, label, colorId } : tag),
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
        inert={!open}
        role="dialog"
        aria-modal="true"
        aria-label={isNew ? t('taskDrawer.newTaskAria') : t('taskDrawer.taskDetailsAria')}
        style={accentStyle}
      >
        <div className="drawer__header">
          <span className="drawer__label">{isNew ? t('taskDrawer.newTaskLabel') : t('taskDrawer.taskLabel')}</span>
          <button className="drawer__close" onClick={onClose} aria-label={t('taskDrawer.close')}>×</button>
        </div>

        <div className="drawer__body">
          {formError && (
            <div className="drawer__error" role="alert">{formError}</div>
          )}
          {readOnly && (
            <p className="drawer__readonly-hint">{t('taskDrawer.tutorialHint')}</p>
          )}
          {!isNew && task?.recurrenceSourceId && task.lastCompletedOn && (
            <p className="drawer__readonly-hint">
              {task.relevantFrom
                ? t('recurrence.occurrenceNote', { occurrence: formatShortDate(task.relevantFrom), completed: formatShortDate(task.lastCompletedOn) })
                : t('recurrence.completedNote', { completed: formatShortDate(task.lastCompletedOn) })}
            </p>
          )}
          <fieldset className="drawer__fieldset" disabled={readOnly}>
          <input
            className={`field__title-input${errors.title ? ' field__input--error' : ''}`}
            value={form.title}
            onChange={e => { setForm(f => ({ ...f, title: e.target.value })); clearError('title'); }}
            placeholder={t('taskDrawer.titlePlaceholder')}
            autoFocus={isNew}
            aria-invalid={errors.title ? true : undefined}
          />
          {errors.title && <p className="field__error">{errors.title}</p>}

          <div className="field">
            <label className="field__label">{t('taskDrawer.category')}</label>
            <div className="cat-picker" role="radiogroup" aria-label={t('taskDrawer.category')}>
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
            <label className="field__label">{t('taskDrawer.priority')}</label>
            <select
              className="field__select"
              value={form.priority ?? ''}
              onChange={e => setForm(f => ({ ...f, priority: (e.target.value as Task['priority']) || undefined }))}
            >
              <option value="">{t('taskDrawer.priorityNone')}</option>
              <option value="high">{t('taskDrawer.priorityHigh')}</option>
              <option value="medium">{t('taskDrawer.priorityMedium')}</option>
              <option value="low">{t('taskDrawer.priorityLow')}</option>
            </select>
          </div>

          {!isNew && task && members.length > 1 && (
            <div className="field">
              <label className="field__label">{t('taskDrawer.assignee')}</label>
              <select
                className="field__select"
                value={task.assigneeUserId ?? ''}
                onChange={e => onSetAssignee(task.id, e.target.value || null)}
              >
                <option value="">{t('taskDrawer.unassigned')}</option>
                {members.map(m => (
                  <option key={m.userId} value={m.userId}>
                    {m.userId === currentUserId ? t('taskDrawer.assigneeYou', { name: m.displayName }) : m.displayName}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="field">
            <label className="field__label">{t('taskDrawer.description')}</label>
            {open ? (
              <Suspense fallback={<div className="field__textarea" aria-busy="true" style={{ minHeight: 96 }} />}>
                <NoteEditor
                  value={form.description ?? ''}
                  onChange={md => { setForm(f => ({ ...f, description: md })); clearError('description'); }}
                  placeholder={t('taskDrawer.descriptionPlaceholder')}
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
              <label className="field__label">{t('taskDrawer.link')}</label>
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
                    placeholder={t('taskDrawer.urlPlaceholder')}
                    aria-invalid={errors.url ? true : undefined}
                  />
                  {errors.url && <p className="field__error">{errors.url}</p>}
                </>
              )}
            </div>
          )}

          <div className="field">
            <label className="field__label">{t('recurrence.repeats')}</label>
            <Toggle
              checked={form.recurrence != null}
              onChange={setRepeats}
              label={t('recurrence.repeatsToggle')}
            />
          </div>

          {form.recurrence ? (
            <RecurrenceFields
              recurrence={form.recurrence}
              relevantFrom={form.relevantFrom ?? ''}
              error={errors.recurrence}
              onChange={next => {
                clearError('recurrence');
                setForm(f => ({ ...f, recurrence: next.recurrence, relevantFrom: next.relevantFrom }));
              }}
            />
          ) : (
            <div className="row-2">
              <div className="field">
                <label className="field__label">{t('taskDrawer.deadline')}</label>
                <input
                  className="field__input"
                  type="date"
                  value={form.deadline}
                  onChange={e => setForm(f => ({ ...f, deadline: e.target.value }))}
                />
              </div>
              <div className="field">
                <label className="field__label">{t('taskDrawer.availableFrom')}</label>
                <input
                  className="field__input"
                  type="date"
                  value={form.relevantFrom}
                  onChange={e => setForm(f => ({ ...f, relevantFrom: e.target.value }))}
                />
              </div>
            </div>
          )}

          <div className="field">
            <label className="field__label">{t('taskDrawer.estMinutes')}</label>
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
              placeholder={t('taskDrawer.estMinutesPlaceholder')}
            />
          </div>

          <div className="field">
            <label className="field__label">
              {t('taskDrawer.tags')}{' '}
              <span className="field__label-hint">{t('taskDrawer.tagsCount', { count: form.tags.length })}</span>
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
                  <span className="tape-editor__hint">{t('taskDrawer.noTagsYet')}</span>
                )}
              </div>

              {form.tags.length < 3 && !showTagForm && tagSuggestions.length > 0 && (
                <div className="tape-quickadd">
                  <span className="tape-quickadd__label">{t('taskDrawer.addFromYourTags')}</span>
                  <div className="tape-quickadd__row">
                    {(tagsExpanded ? tagSuggestions : tagSuggestions.slice(0, 3)).map(tag => (
                      <button
                        key={tag.id}
                        type="button"
                        className="tape-quickadd__chip"
                        onClick={() => addExistingTag(tag)}
                        aria-label={t('taskDrawer.addTagAria', { label: tag.label })}
                        title={t('taskDrawer.addTagTitle', { label: tag.label })}
                      >
                        <WashiTape tag={tag} idSeed={'sugg-' + tag.id} />
                      </button>
                    ))}
                    {tagSuggestions.length > 3 && (
                      <button
                        type="button"
                        className="tape-quickadd__more"
                        onClick={() => setTagsExpanded(v => !v)}
                        aria-expanded={tagsExpanded}
                      >
                        {tagsExpanded ? t('taskDrawer.showLess') : t('taskDrawer.moreTags', { count: tagSuggestions.length - 3 })}
                      </button>
                    )}
                  </div>
                </div>
              )}

              {form.tags.length < 3 && !showTagForm && (
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => setShowTagForm(true)}
                >
                  {t('taskDrawer.addTag')}
                </button>
              )}

              {showTagForm && (() => {
                const isExisting = tagId !== null || availableTags.some(tag => tag.label.toLowerCase() === tagLabel.trim().toLowerCase());
                return (
                <div className="tag-form">
                  <Autocomplete
                    value={tagLabel}
                    onChange={(val) => {
                      setTagLabel(val);
                      setTagId(null);
                      const existing = availableTags.find(tag => tag.label.toLowerCase() === val.trim().toLowerCase());
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
                      .filter(tag => !form.tags.some(ft => ft.id === tag.id || ft.label.toLowerCase() === tag.label.toLowerCase()))
                      .map(tag => ({
                        id: tag.id,
                        label: tag.label,
                        colorId: tag.colorId,
                        color: TAG_PALETTE.find(c => c.id === tag.colorId)?.text || 'currentColor'
                      }))}
                    placeholder={t('taskDrawer.tagLabelPlaceholder')}
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
                      {t('taskDrawer.add')}
                    </button>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() => setShowTagForm(false)}
                    >
                      {t('taskDrawer.cancel')}
                    </button>
                  </div>
                </div>
                );
              })()}
            </div>
          </div>

          {aiEnabled && (
            <div className="field">
              <label className="field__label">
                {t('taskDrawer.visibility')}
                <HelpTip text={t('taskDrawer.visibilityHelp')} />
              </label>
              <Toggle
                checked={form.hiddenFromAssistant ?? false}
                onChange={next => setForm(f => ({ ...f, hiddenFromAssistant: next }))}
                label={t('taskDrawer.hideFromAssistant')}
              />
            </div>
          )}
          </fieldset>
        </div>

        {!isNew && task && (
          <div className="drawer__timestamps">
            <span>{t('taskDrawer.created', { relative: formatRelative(task.createdAt) })}</span>
            {task.updatedAt && <span>{t('taskDrawer.updated', { relative: formatRelative(task.updatedAt) })}</span>}
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
                    {t('taskDrawer.unarchive')}
                  </button>
                ) : task?.status === 'done' ? (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => { onMarkTodo(task!.id); onClose(); }}
                  >
                    {t('taskDrawer.markTodo')}
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => { onMarkDone(task!.id); onClose(); }}
                  >
                    {t('taskDrawer.markDone')}
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn--danger btn--sm"
                  onClick={() => { onDelete(task!.id); onClose(); }}
                >
                  {t('taskDrawer.delete')}
                </button>
              </>
            )}
          </div>
          <div className="drawer__footer-right">
            <button type="button" className="btn btn--ghost" onClick={onClose}>{readOnly ? t('taskDrawer.close') : t('taskDrawer.cancel')}</button>
            {!readOnly && (
              <button
                type="button"
                className="btn btn--primary"
                onClick={handleSave}
                disabled={!form.title.trim() || !form.categoryId}
              >
                {isNew ? t('taskDrawer.pinItUp') : t('taskDrawer.save')}
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

import { useState, useEffect, useId, useRef, Suspense, lazy } from 'react';
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
import { generateId, formatDuration, formatRelative } from '../utils';
import {
  defaultRule, firstOccurrence, formatShortDate, isRuleValid, normalizeRule, recurrenceSummary, todayIso,
} from '../recurrence';
import { resolveTaskLink, linkLabel } from '../taskLink';
import i18n from '../i18n';
import styles from './TaskDrawer.module.css';

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
  /** Resolves with the saved task (when the caller has it); rejects to keep the drawer open. */
  onSave: (task: Omit<Task, 'sortKey'>) => void | Promise<Task | void>;
  onDelete: (id: string) => void;
  /** `latest` is the task as just saved from this drawer, so the status flip doesn't replace it with a stale copy. */
  onMarkDone: (id: string, latest?: Task) => void;
  onMarkTodo: (id: string, latest?: Task) => void;
  onSetAssignee: (id: string, userId: string | null) => void;
  /** Persists a board-wide tag rename/recolor (from the inline tag-edit modal) and refreshes tags/tasks. */
  onUpdateTag?: (tagId: string, label: string, colorId: TagColorId) => void;
  /** Follows a (read-only) task's link field — internal routes/actions can't be plain anchors. */
  onFollowLink?: (task: Task) => void;
}

type FormState = Omit<Task, 'id' | 'createdAt' | 'sortKey'>;

/** One-tap estimates; anything else goes through the custom-minutes input. */
const TIME_PRESETS = [30, 60, 120];
const PRIORITIES: { value: Task['priority']; labelKey: string }[] = [
  { value: undefined, labelKey: 'taskDrawer.priorityNone' },
  { value: 'low', labelKey: 'taskDrawer.priorityLow' },
  { value: 'medium', labelKey: 'taskDrawer.priorityMedium' },
  { value: 'high', labelKey: 'taskDrawer.priorityHigh' },
];

const isCustomTime = (minutes: number | undefined) => minutes != null && !TIME_PRESETS.includes(minutes);
type FieldErrors = Partial<Record<'title' | 'url' | 'description' | 'recurrence', string>>;

// Mirrors the server-side constraints on CreateBacklogTaskRequest so the user gets an inline,
// field-specific reason before submitting (rather than a generic "invalid request" toast).
const URL_PATTERN = /^https?:\/\//i;

/** `storedUrl` is the link the task was loaded with; see the unchanged-link exemption below. */
function validate(form: FormState, storedUrl?: string | null): FieldErrors {
  const errors: FieldErrors = {};
  const title = form.title.trim();
  if (!title) errors.title = i18n.t('taskDrawer.validation.titleRequired');
  else if (title.length > 500) errors.title = i18n.t('taskDrawer.validation.titleTooLong');
  if (form.description && form.description.length > 5000) {
    errors.description = i18n.t('taskDrawer.validation.descriptionTooLong');
  }
  const url = form.url?.trim();
  if (url) {
    // A link left exactly as loaded is allowed, because the server now allows it (see `TaskUrl`):
    // only a *new* link has to be http(s). Keeping the two rules in step is the point of mirroring
    // them here — otherwise this form refuses a save the API would have accepted, with no way for
    // the user to clear the error except retyping a link they never touched.
    if (url !== storedUrl && !URL_PATTERN.test(url)) errors.url = i18n.t('taskDrawer.validation.urlInvalid');
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

function formFromTask(task: Task): FormState {
  return {
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
  };
}

/** The form as it is sent to the server: trimmed, with blanks dropped. */
function toPayload(form: FormState): FormState {
  return {
    ...form,
    title: form.title.trim(),
    description: form.description?.trim() || undefined,
    url: form.url?.trim() || undefined,
    deadline: form.deadline || undefined,
    relevantFrom: form.relevantFrom || undefined,
    estimatedMinutes: form.estimatedMinutes || undefined,
    recurrence: form.recurrence ? normalizeRule(form.recurrence) : null,
  };
}

// Compared as payloads, so whitespace-only edits (which the save would trim away) don't count.
function isDirty(form: FormState, task: Task): boolean {
  return JSON.stringify(toPayload(form)) !== JSON.stringify(toPayload(formFromTask(task)));
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
  // The rarely-used fields fold away; a repeating task opens with them shown, since its schedule lives there.
  const [moreOpen, setMoreOpen] = useState(false);
  const [customTime, setCustomTime] = useState(false);
  // Dismissing and the Save button can race (a double-click on ×); one save at a time.
  const savingRef = useRef(false);
  const titleId = useId();
  const moreId = useId();

  const targetKey = open ? (task?.id ?? '__new__') : null;
  if (targetKey !== formKey) {
    setFormKey(targetKey);
    setErrors({});
    setFormError(null);
    if (targetKey !== null) {
      setMoreOpen(!isNew && task?.recurrence != null);
      setCustomTime(!isNew && isCustomTime(task?.estimatedMinutes));
      setForm(task ? formFromTask(task) : makeEmpty(defaultCategoryId));
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

  /** Resolves `false` when the save was refused or failed (the drawer then stays open with the reason). */
  const handleSave = async (): Promise<Task | void | false> => {
    if (!form.categoryId) return false;
    setFormError(null);
    const found = validate(form, task?.url);
    if (found.title || found.url || found.description || found.recurrence) {
      setErrors(found);
      // Those two fields live in the folded section; don't report an error the user can't see.
      if (found.url || found.recurrence) setMoreOpen(true);
      return false;
    }
    if (savingRef.current) return false;
    savingRef.current = true;
    const now = new Date().toISOString();
    try {
      return await onSave({
        id: task?.id ?? generateId(),
        createdAt: task?.createdAt ?? now,
        ...toPayload(form),
      });
    } catch (e) {
      // The drawer owns error display here (the save call opts out of the global toast). Server-side
      // field reasons go inline; anything else shows as a form-level banner. The drawer stays open.
      const mapped = serverFieldErrors(e);
      if (mapped) {
        setErrors(mapped);
        if (mapped.url) setMoreOpen(true);
      } else {
        setFormError(e instanceof ApiError ? e.userMessage : t('taskDrawer.saveError'));
      }
      return false;
    } finally {
      savingRef.current = false;
    }
  };

  // Edits to an existing task are kept when the drawer is dismissed (×, overlay click, or a status
  // action). Escape and Cancel stay explicit discards, and a new task is only created by its button.
  const pendingEdits = !isNew && !readOnly && !!task && isDirty(form, task);

  const dismiss = async () => {
    if (!pendingEdits) { onClose(); return; }
    // Board's onSave closes the drawer on success; on failure it stays open with the reason.
    await handleSave();
  };

  // Saves pending edits before the status action, and hands it the saved task so it builds on it.
  const runStatusAction = async (action: (id: string, latest?: Task) => void) => {
    if (!task) return;
    if (!pendingEdits) { action(task.id); onClose(); return; }
    const saved = await handleSave();
    if (saved === false) return;
    action(task.id, saved || undefined);
    onClose();
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

  // Tapes loaded from a saved task carry no id (the task API embeds only label+color), so resolve the
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

  const statusAction = task?.status === 'archived'
    ? { label: t('taskDrawer.unarchive'), run: onMarkTodo }
    : task?.status === 'done'
      ? { label: t('taskDrawer.markTodo'), run: onMarkTodo }
      : { label: t('taskDrawer.markDone'), run: onMarkDone };

  const setEstimate = (minutes: number | undefined) => setForm(f => ({ ...f, estimatedMinutes: minutes }));

  // What the folded section holds; filled-in fields are emphasised so nothing set there is out of sight.
  const moreSummary: { key: string; label: string; set: boolean }[] = [
    {
      key: 'tags',
      label: form.tags.length > 0 ? `${t('taskDrawer.tags')} ${t('taskDrawer.tagsCount', { count: form.tags.length })}` : t('taskDrawer.tags'),
      set: form.tags.length > 0,
    },
    ...(!readOnly ? [{ key: 'link', label: t('taskDrawer.link'), set: !!form.url?.trim() }] : []),
    { key: 'repeat', label: t('recurrence.repeats'), set: form.recurrence != null },
    ...(form.recurrence ? [] : [{ key: 'from', label: t('taskDrawer.availableFrom'), set: !!form.relevantFrom }]),
    ...(aiEnabled ? [{ key: 'hidden', label: t('taskDrawer.hideFromAssistant'), set: !!form.hiddenFromAssistant }] : []),
  ];

  return (
    <>
      <div className={`overlay${open ? ' overlay--open' : ''}`} onClick={() => void dismiss()} />
      <aside
        className={`drawer ${styles.drawer}${open ? ' drawer--open' : ''}`}
        inert={!open}
        role="dialog"
        aria-modal="true"
        aria-label={isNew ? t('taskDrawer.newTaskAria') : t('taskDrawer.taskDetailsAria')}
        style={accentStyle}
      >
        <div className={`drawer__header ${styles.header}`}>
          <span className="drawer__label">{isNew ? t('taskDrawer.newTaskLabel') : t('taskDrawer.taskLabel')}</span>
          <div className={styles.headerActions}>
            {!isNew && task && (
              <>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => void runStatusAction(statusAction.run)}
                >
                  {statusAction.label}
                </button>
                <button
                  type="button"
                  className={styles.iconBtn}
                  onClick={() => { onDelete(task.id); onClose(); }}
                  aria-label={t('taskDrawer.delete')}
                  title={t('taskDrawer.delete')}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
                  </svg>
                </button>
              </>
            )}
            <button className="drawer__close" onClick={() => void dismiss()} aria-label={t('taskDrawer.close')}>×</button>
          </div>
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
          <div className="field">
            <label className={`field__label ${styles.titleLabel}`} htmlFor={titleId}>{t('taskDrawer.titleLabel')}</label>
            <input
              id={titleId}
              className={`${styles.titleInput}${errors.title ? ' field__input--error' : ''}`}
              value={form.title}
              onChange={e => { setForm(f => ({ ...f, title: e.target.value })); clearError('title'); }}
              onKeyDown={e => {
                // Enter saves: the title is all a quick add needs. Not mid-composition (IME).
                if (e.key === 'Enter' && !e.nativeEvent.isComposing) { e.preventDefault(); void handleSave(); }
              }}
              placeholder={t('taskDrawer.titlePlaceholder')}
              autoFocus={isNew}
              aria-invalid={errors.title ? true : undefined}
            />
            {errors.title
              ? <p className="field__error">{errors.title}</p>
              : isNew && <p className={styles.titleHint}>{t('taskDrawer.titleHint')}</p>}
          </div>

          <div className="field">
            <span className="field__label" id={`${titleId}-cat`}>{t('taskDrawer.category')}</span>
            <div className={styles.catGrid} role="radiogroup" aria-labelledby={`${titleId}-cat`}>
              {categories.map(cat => {
                const sw = PAPER_SWATCHES.find(s => s.id === cat.swatchId) ?? PAPER_SWATCHES[6];
                const selected = cat.id === form.categoryId;
                return (
                  <button
                    key={cat.id}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    className={`${styles.cat}${selected ? ` ${styles.catSelected}` : ''}`}
                    style={selected ? { background: sw.paper } : undefined}
                    onClick={() => setForm(f => ({ ...f, categoryId: cat.id }))}
                  >
                    <span className={styles.catSwatch} style={{ background: sw.paper, borderColor: sw.edge }} />
                    <span className={styles.catLabel}>{cat.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="field">
            <span className="field__label" id={`${titleId}-prio`}>{t('taskDrawer.priority')}</span>
            <div className={styles.segmented} role="radiogroup" aria-labelledby={`${titleId}-prio`}>
              {PRIORITIES.map(p => (
                <button
                  key={p.labelKey}
                  type="button"
                  role="radio"
                  aria-checked={form.priority === p.value}
                  className={styles.segment}
                  onClick={() => setForm(f => ({ ...f, priority: p.value }))}
                >
                  {t(p.labelKey)}
                </button>
              ))}
            </div>
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

          {readOnly && taskLink && (
            <div className="field">
              <label className="field__label">{t('taskDrawer.link')}</label>
              {/* Internal/action links can't be plain anchors; let Board resolve them. An <a> stays
                  clickable inside the disabled fieldset (only form controls are disabled). */}
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
            </div>
          )}

          <div className={styles.whenRow}>
            <div className="field">
              {form.recurrence ? (
                <>
                  <span className="field__label">{t('recurrence.repeats')}</span>
                  <button type="button" className={styles.repeatSummary} onClick={() => setMoreOpen(true)}>
                    {recurrenceSummary(form.recurrence, form.relevantFrom || undefined)}
                  </button>
                </>
              ) : (
                <>
                  <label className="field__label" htmlFor={`${titleId}-deadline`}>{t('taskDrawer.deadline')}</label>
                  <input
                    id={`${titleId}-deadline`}
                    className="field__input"
                    type="date"
                    value={form.deadline}
                    onChange={e => setForm(f => ({ ...f, deadline: e.target.value }))}
                  />
                </>
              )}
            </div>
            <div className="field">
              <span className="field__label" id={`${titleId}-time`}>{t('taskDrawer.timeNeeded')}</span>
              <div className={styles.pills} role="group" aria-labelledby={`${titleId}-time`}>
                {TIME_PRESETS.map(minutes => {
                  const on = !customTime && form.estimatedMinutes === minutes;
                  return (
                    <button
                      key={minutes}
                      type="button"
                      className={styles.pill}
                      aria-pressed={on}
                      onClick={() => { setCustomTime(false); setEstimate(on ? undefined : minutes); }}
                    >
                      {formatDuration(minutes)}
                    </button>
                  );
                })}
                <button
                  type="button"
                  className={styles.pill}
                  aria-pressed={customTime}
                  aria-label={t('taskDrawer.timeCustom')}
                  title={t('taskDrawer.timeCustom')}
                  onClick={() => setCustomTime(v => !v)}
                >
                  …
                </button>
              </div>
              {customTime && (
                <input
                  className="field__input"
                  type="number"
                  min={0}
                  step={15}
                  value={form.estimatedMinutes ?? ''}
                  onChange={e => setEstimate(e.target.value ? Number(e.target.value) : undefined)}
                  placeholder={t('taskDrawer.timeMinutesPlaceholder')}
                  aria-label={t('taskDrawer.timeMinutesPlaceholder')}
                />
              )}
            </div>
          </div>
          </fieldset>

          <div className={styles.more}>
            <button
              type="button"
              className={styles.moreToggle}
              aria-expanded={moreOpen}
              aria-controls={moreId}
              onClick={() => setMoreOpen(v => !v)}
            >
              <span className={styles.moreText}>
                <span className={styles.moreTitle}>{t('taskDrawer.moreOptions')}</span>
                {!moreOpen && (
                  <span className={styles.moreSummary}>
                    {moreSummary.map((item, i) => (
                      <span key={item.key}>
                        {i > 0 && ' · '}
                        <span className={item.set ? styles.moreSummarySet : undefined}>{item.label}</span>
                      </span>
                    ))}
                  </span>
                )}
              </span>
              <svg className={styles.moreChevron} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M6 9l6 6 6-6" />
              </svg>
            </button>

            {moreOpen && (
          <fieldset id={moreId} className={styles.moreBody} disabled={readOnly}>
          {!readOnly && (
            <div className="field">
              <label className="field__label" htmlFor={`${titleId}-url`}>{t('taskDrawer.link')}</label>
              <input
                id={`${titleId}-url`}
                className={`field__input${errors.url ? ' field__input--error' : ''}`}
                type="url"
                value={form.url}
                onChange={e => { setForm(f => ({ ...f, url: e.target.value })); clearError('url'); }}
                placeholder={t('taskDrawer.urlPlaceholder')}
                aria-invalid={errors.url ? true : undefined}
              />
              {errors.url && <p className="field__error">{errors.url}</p>}
            </div>
          )}

          <div className="field">
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
            <div className="field">
              <label className="field__label" htmlFor={`${titleId}-from`}>{t('taskDrawer.availableFrom')}</label>
              <input
                id={`${titleId}-from`}
                className="field__input"
                type="date"
                value={form.relevantFrom}
                onChange={e => setForm(f => ({ ...f, relevantFrom: e.target.value }))}
              />
            </div>
          )}
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
            )}
          </div>

          {!isNew && task && (
            <p className={styles.timestamps}>
              <span>{t('taskDrawer.created', { relative: formatRelative(task.createdAt) })}</span>
              {task.updatedAt && <span>{t('taskDrawer.updated', { relative: formatRelative(task.updatedAt) })}</span>}
            </p>
          )}
        </div>

        <div className="drawer__footer">
          <div className="drawer__footer-left" />
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

import { useState } from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { useTranslation } from 'react-i18next';
import type { Task, Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { formatDeadline, isOverdue, formatDuration, rotationFromId, notePreview } from '../utils';
import { WashiTape } from './WashiTape';
import { MarkdownRenderer } from './MarkdownRenderer';
import { resolveTaskLink, linkLabel } from '../taskLink';
import type { AssigneeChipInfo } from './PostItNote';
import { formatShortDate, waitingUntil } from '../recurrence';
import styles from './TaskLine.module.css';

interface TaskLineProps {
  task: Task;
  category: Category | undefined;
  leaving?: boolean;
  inCurrentPlan?: boolean;
  assignee?: AssigneeChipInfo | null;
  /** When false, hand-reorder is off (a non-custom sort is active) — dragging is disabled. */
  draggable?: boolean;
  onClick: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
  onFollowLink?: () => void;
  /** Toggles the description's N-th checklist item; omitted, the checkboxes are read-only. */
  onToggleCheckbox?: (index: number) => void;
}

const FALLBACK_SWATCH: PaperSwatchId = 'cream';

export function TaskLine({
  task,
  category,
  leaving = false,
  inCurrentPlan = false,
  assignee = null,
  draggable = true,
  onClick,
  onContextMenu,
  onFollowLink,
  onToggleCheckbox,
}: TaskLineProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  const swatchId = category?.swatchId ?? FALLBACK_SWATCH;
  const swatch = PAPER_SWATCHES.find(s => s.id === swatchId) ?? PAPER_SWATCHES[6];
  const tilt = rotationFromId(task.id, 1.2);

  const tags = task.tags.slice(0, 3);
  const link = onFollowLink != null ? resolveTaskLink(task.url) : null;
  const preview = task.description ? notePreview(task.description) : '';
  // A dog-ear (and thus the reveal) appears whenever there's anything folded away beyond the row.
  const expandable = !!task.description || tags.length > 0 || link != null;

  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: task.id, disabled: !draggable });

  const classNames = [
    styles.line,
    open ? styles.open : '',
    leaving ? styles.leaving : '',
    inCurrentPlan ? styles.inPlan : '',
    task.status === 'done' ? styles.done : '',
  ].filter(Boolean).join(' ');

  // Base CSS applies rotate(var(--rot)); while dnd-kit drives an inline transform we compose the
  // tilt back in ourselves so it isn't stripped mid-drag.
  const inlineTransform = transform
    ? `translate3d(${transform.x}px, ${transform.y}px, 0) rotate(${tilt.toFixed(2)}deg)`
    : undefined;

  const style: React.CSSProperties = {
    ['--paper'      as string]: swatch.paper,
    ['--paper-edge' as string]: swatch.edge,
    ['--paper-ink'  as string]: swatch.ink,
    ['--rot'        as string]: `${tilt.toFixed(2)}deg`,
    transform: inlineTransform,
    transition,
    opacity: isDragging ? 0.4 : undefined,
  };

  return (
    <article
      ref={setNodeRef}
      className={classNames}
      style={style}
      data-task-id={task.id}
      {...attributes}
      {...listeners}
      role="listitem"
      tabIndex={0}
      aria-label={t('taskCard.openTask', { title: task.title })}
      onClick={onClick}
      onContextMenu={onContextMenu ? e => { e.preventDefault(); onContextMenu(e); } : undefined}
      // Only the row itself opens the task on Enter/Space — child buttons (dog-ear, link) handle their own keys.
      onKeyDown={e => {
        if (e.target !== e.currentTarget) return;
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); }
      }}
    >
      {inCurrentPlan && <span className={styles.srOnly}>{t('taskCard.inCurrentPlan')}</span>}

      {task.priority === 'high'
        ? <span className={styles.bang} aria-label={t('taskCard.highPriority')}>!</span>
        : <span className={styles.cat} title={category ? t('taskCard.categoryTitle', { label: category.label }) : undefined}>{category?.label ?? ''}</span>}

      <div className={styles.main}>
        <div className={styles.title}>{task.title}</div>
        {preview && (
          <div className={styles.preview}>
            <NoteIcon />
            <span className={styles.previewTxt}>{preview}</span>
          </div>
        )}
      </div>

      <div className={styles.meta}>
        {assignee && (
          <span
            className={`${styles.who} ${assignee.isMe ? styles.whoMe : ''}`}
            title={assignee.isMe ? t('taskCard.claimedByYouWithName', { name: assignee.name }) : t('taskCard.assignedTo', { name: assignee.name })}
            aria-label={assignee.isMe ? t('taskCard.claimedByYou') : t('taskCard.assignedTo', { name: assignee.name })}
          >
            {assignee.initials}
          </span>
        )}
        {task.estimatedMinutes != null && (
          <span className={styles.dur}><ClockIcon /> {formatDuration(task.estimatedMinutes)}</span>
        )}
        {task.recurrence && (
          <span title={t('recurrence.repeats')}>
            <span aria-hidden="true">↻</span>
            {waitingUntil(task)
              ? ` ${t('recurrence.cardNext', { date: formatShortDate(waitingUntil(task)!) })}`
              : <span className={styles.srOnly}>{t('recurrence.repeats')}</span>}
          </span>
        )}
        {task.deadline && !waitingUntil(task) && (
          <span className={isOverdue(task.deadline, task.status !== 'todo') ? styles.overdue : undefined}>
            <CalIcon /> {formatDeadline(task.deadline, task.status !== 'todo')}
          </span>
        )}
      </div>

      {expandable && (
        <button
          type="button"
          className={styles.dogear}
          aria-label={open ? t('taskCard.foldAway') : t('taskCard.peekNote')}
          aria-expanded={open}
          title={open ? t('taskCard.foldAway') : t('taskCard.peekNote')}
          onPointerDown={e => e.stopPropagation()}
          onClick={e => { e.stopPropagation(); setOpen(o => !o); }}
        />
      )}

      {expandable && (
        <div className={styles.fold}>
          <div className={styles.foldInner}>
            <div className={styles.foldBody}>
              {task.description && (
                <MarkdownRenderer content={task.description} showExpandButton={false} onToggleCheckbox={onToggleCheckbox} />
              )}
              {link && (
                <button
                  type="button"
                  className={styles.link}
                  aria-label={t('taskCard.openLink', { label: linkLabel(link) })}
                  title={link.kind === 'external' ? link.href : linkLabel(link)}
                  onPointerDown={e => e.stopPropagation()}
                  onClick={e => { e.stopPropagation(); onFollowLink?.(); }}
                >
                  <LinkIcon />
                  <span>{linkLabel(link)}</span>
                </button>
              )}
              {tags.length > 0 && (
                <div className={styles.tapes}>
                  {tags.map((tag, i) => (
                    <WashiTape key={i} tag={tag} index={i} idSeed={task.id} />
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </article>
  );
}

function NoteIcon() {
  return (
    <svg className={styles.previewIcon} width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden>
      <path d="M2.5 2.5h7v5l-2 2h-5z" />
      <path d="M9.5 7.5h-2v2" />
    </svg>
  );
}

function CalIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden>
      <rect x="1.5" y="2.5" width="9" height="8" rx="1" />
      <path d="M4 1v2M8 1v2M1.5 5h9" strokeLinecap="round" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden>
      <circle cx="6" cy="6" r="4.5" />
      <path d="M6 3.5V6l1.8 1.2" strokeLinecap="round" />
    </svg>
  );
}

function LinkIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M4.5 2.5H2.5v7h7v-2" />
      <path d="M7 2.5h2.5V5" />
      <path d="M9.5 2.5L5.5 6.5" />
    </svg>
  );
}

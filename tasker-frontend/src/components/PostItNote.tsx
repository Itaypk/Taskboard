import { useSortable } from '@dnd-kit/sortable';
import type { Task, Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { formatDeadline, isOverdue, formatDuration, rotationFromId } from '../utils';
import { WashiTape } from './WashiTape';
import { MarkdownRenderer } from './MarkdownRenderer';
import { resolveTaskLink, linkLabel } from '../taskLink';
import styles from './PostItNote.module.css';

/** Resolved claimer for the assignee chip; only supplied on shared boards. */
export interface AssigneeChipInfo {
  initials: string;
  name: string;
  isMe: boolean;
}

interface PostItNoteProps {
  task: Task;
  category: Category | undefined;
  leaving?: boolean;
  inCurrentPlan?: boolean;
  assignee?: AssigneeChipInfo | null;
  onClick: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
  /** Follows the task's link field (external/internal/action); only wired when the task has a link. */
  onFollowLink?: () => void;
}

const FALLBACK_SWATCH: PaperSwatchId = 'cream';

export function PostItNote({
  task,
  category,
  leaving = false,
  inCurrentPlan = false,
  assignee = null,
  onClick,
  onContextMenu,
  onFollowLink,
}: PostItNoteProps) {
  const swatchId = category?.swatchId ?? FALLBACK_SWATCH;
  const swatch = PAPER_SWATCHES.find(s => s.id === swatchId) ?? PAPER_SWATCHES[6];

  const rotation = rotationFromId(task.id) * 0.3;
  const tags = task.tags.slice(0, 3);
  const link = onFollowLink != null ? resolveTaskLink(task.url) : null;

  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: task.id });

  const classNames = [
    'note',
    'note--list',
    leaving        ? 'note--leaving'  : '',
    isDragging     ? 'note--dragging' : '',
    inCurrentPlan  ? styles.inPlan    : '',
  ].filter(Boolean).join(' ');

  // The base `.note` CSS applies `rotate(var(--note-rot))`. While dnd-kit is
  // animating the card we set an inline transform, which would otherwise
  // strip that rotation — so we compose translate + rotate ourselves.
  const inlineTransform = transform
    ? `translate3d(${transform.x}px, ${transform.y}px, 0) rotate(${rotation.toFixed(2)}deg)`
    : undefined;

  const style: React.CSSProperties = {
    ['--paper'      as string]: swatch.paper,
    ['--paper-edge' as string]: swatch.edge,
    ['--paper-ink'  as string]: swatch.ink,
    ['--note-rot'   as string]: `${rotation.toFixed(2)}deg`,
    transform: inlineTransform,
    transition,
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
      aria-label={`Open task: ${task.title}`}
      onClick={onClick}
      onContextMenu={onContextMenu ? e => { e.preventDefault(); onContextMenu(e); } : undefined}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
    >
      {task.priority === 'high' && (
        <span className="note__stamp" aria-label="High priority">!</span>
      )}

      {inCurrentPlan && (
        <span className={styles.planSrLabel}>In this week's plan</span>
      )}

      {category && (
        <span className="note__category" title={`Category: ${category.label}`}>
          {category.label}
        </span>
      )}

      <h3 className="note__title">{task.title}</h3>

      {task.description && (
        <div className="note__desc">
          <MarkdownRenderer content={task.description} maxLength={120} showExpandButton={false} />
        </div>
      )}

      <div className="note__meta">
        {task.deadline && (
          <span className={`note__meta-item${isOverdue(task.deadline) ? ' note__meta-item--overdue' : ''}`}>
            <Icon name="cal" /> {formatDeadline(task.deadline)}
          </span>
        )}
        {task.estimatedMinutes != null && (
          <span className="note__meta-item">
            <Icon name="clock" /> {formatDuration(task.estimatedMinutes)}
          </span>
        )}
        {assignee && (
          <span
            className={`${styles.assignee} ${assignee.isMe ? styles.assigneeMe : ''}`}
            title={assignee.isMe ? `Claimed by you (${assignee.name})` : `Assigned to ${assignee.name}`}
            aria-label={assignee.isMe ? `Claimed by you` : `Assigned to ${assignee.name}`}
          >
            {assignee.initials}
          </span>
        )}
      </div>

      {tags.length > 0 && (
        <div className="note__tapes">
          {tags.map((tag, i) => (
            <WashiTape key={i} tag={tag} index={i} idSeed={task.id} />
          ))}
        </div>
      )}

      {link && (
        <button
          type="button"
          className={styles.linkRow}
          aria-label={`Open link: ${linkLabel(link)}`}
          title={link.kind === 'external' ? link.href : linkLabel(link)}
          onPointerDown={e => e.stopPropagation()}
          onClick={e => { e.stopPropagation(); onFollowLink?.(); }}
        >
          <LinkIcon />
          <span className={styles.linkLabel}>{linkLabel(link)}</span>
        </button>
      )}

      <span className="note__curl" aria-hidden />

      <span className="note__drag-handle" aria-hidden title="Drag to reorder">
        ⠿
      </span>

      {onContextMenu && (
        <button
          className={styles.menuBtn}
          aria-label="Task actions"
          onPointerDown={e => e.stopPropagation()}
          onClick={e => { e.stopPropagation(); onContextMenu(e as unknown as React.MouseEvent); }}
        >
          ⋮
        </button>
      )}
    </article>
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

function Icon({ name }: { name: 'cal' | 'clock' }) {
  if (name === 'cal') {
    return (
      <svg width="10" height="10" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden>
        <rect x="1.5" y="2.5" width="9" height="8" rx="1" />
        <path d="M4 1v2M8 1v2M1.5 5h9" strokeLinecap="round" />
      </svg>
    );
  }
  return (
    <svg width="10" height="10" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden>
      <circle cx="6" cy="6" r="4.5" />
      <path d="M6 3.5V6l1.8 1.2" strokeLinecap="round" />
    </svg>
  );
}

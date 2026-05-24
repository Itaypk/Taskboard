import { useSortable } from '@dnd-kit/sortable';
import type { Task, Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { formatDeadline, isOverdue, formatDuration, rotationFromId } from '../utils';
import { WashiTape } from './WashiTape';
import { MarkdownRenderer } from './MarkdownRenderer';
import styles from './PostItNote.module.css';

interface PostItNoteProps {
  task: Task;
  category: Category | undefined;
  leaving?: boolean;
  inCurrentPlan?: boolean;
  onClick: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
}

const FALLBACK_SWATCH: PaperSwatchId = 'cream';

export function PostItNote({
  task,
  category,
  leaving = false,
  inCurrentPlan = false,
  onClick,
  onContextMenu,
}: PostItNoteProps) {
  const swatchId = category?.swatchId ?? FALLBACK_SWATCH;
  const swatch = PAPER_SWATCHES.find(s => s.id === swatchId) ?? PAPER_SWATCHES[6];

  const rotation = rotationFromId(task.id) * 0.3;
  const tags = task.tags.slice(0, 3);

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
      </div>

      {tags.length > 0 && (
        <div className="note__tapes">
          {tags.map((tag, i) => (
            <WashiTape key={i} tag={tag} index={i} idSeed={task.id} />
          ))}
        </div>
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

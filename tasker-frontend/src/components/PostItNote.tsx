import { useRef } from 'react';
import type { DragEvent } from 'react';
import type { Task, Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { formatDeadline, isOverdue, formatDuration, rotationFromId } from '../utils';
import { WashiTape } from './WashiTape';

interface PostItNoteProps {
  task: Task;
  category: Category | undefined;
  leaving?: boolean;
  isDragTarget?: boolean;
  dropPosition?: 'before' | 'after';
  onClick: () => void;
  onDragStart: (taskId: string) => void;
  onDragOver: (taskId: string, position: 'before' | 'after') => void;
  onDragEnd: () => void;
  onDrop: (targetId: string, position: 'before' | 'after') => void;
}

const FALLBACK_SWATCH: PaperSwatchId = 'cream';

export function PostItNote({
  task,
  category,
  leaving = false,
  isDragTarget = false,
  dropPosition,
  onClick,
  onDragStart,
  onDragOver,
  onDragEnd,
  onDrop,
}: PostItNoteProps) {
  const swatchId = category?.swatchId ?? FALLBACK_SWATCH;
  const swatch = PAPER_SWATCHES.find(s => s.id === swatchId) ?? PAPER_SWATCHES[6];

  // Use a subtle rotation for visual character, but keep it small in list view.
  const rotation = rotationFromId(task.id) * 0.3;
  const tags = task.tags.slice(0, 3);

  const cardRef = useRef<HTMLElement>(null);

  // Decide whether the cursor is on the "before" or "after" side of this card.
  // The board is a row-major grid, so neighbors can be horizontal OR vertical.
  // We use a diagonal split (anti-diagonal of the card): the upper-left
  // triangle is "before", the lower-right triangle is "after". That single
  // rule degrades to a top/bottom split for vertically-stacked cards and to
  // a left/right split for horizontally-stacked cards.
  const getDropPosition = (clientX: number, clientY: number): 'before' | 'after' => {
    if (!cardRef.current) return 'after';
    const rect = cardRef.current.getBoundingClientRect();
    const fx = (clientX - rect.left) / rect.width;
    const fy = (clientY - rect.top)  / rect.height;
    return fx + fy < 1 ? 'before' : 'after';
  };

  const classNames = [
    'note',
    'note--list',
    leaving         ? 'note--leaving'    : '',
    isDragTarget && dropPosition === 'before' ? 'note--drop-before' : '',
    isDragTarget && dropPosition === 'after'  ? 'note--drop-after'  : '',
  ].filter(Boolean).join(' ');

  return (
    <article
      ref={cardRef}
      className={classNames}
      style={{
        ['--paper'     as string]: swatch.paper,
        ['--paper-edge' as string]: swatch.edge,
        ['--paper-ink' as string]: swatch.ink,
        ['--note-rot'  as string]: `${rotation.toFixed(2)}deg`,
      }}
      role="listitem"
      data-task-id={task.id}
      draggable
      // ---- click / keyboard ----
      onClick={onClick}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
      tabIndex={0}
      aria-label={`Open task: ${task.title}`}
      // ---- drag source ----
      onDragStart={(e: DragEvent<HTMLElement>) => {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', task.id);
        const card = e.currentTarget;
        // Defer dimming the source until after the browser snapshots the drag
        // image, so the ghost shows the card at full opacity.
        requestAnimationFrame(() => card.classList.add('note--dragging'));
        onDragStart(task.id);
      }}
      onDragEnd={(e: DragEvent<HTMLElement>) => {
        e.currentTarget.classList.remove('note--dragging');
        onDragEnd();
      }}
      // ---- drop target ----
      onDragOver={(e: DragEvent<HTMLElement>) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        onDragOver(task.id, getDropPosition(e.clientX, e.clientY));
      }}
      onDrop={(e: DragEvent<HTMLElement>) => {
        e.preventDefault();
        onDrop(task.id, getDropPosition(e.clientX, e.clientY));
      }}
    >
      {task.priority === 'high' && (
        <span className="note__stamp" aria-label="High priority">!</span>
      )}

      {category && (
        <span className="note__category" title={`Category: ${category.label}`}>
          {category.label}
        </span>
      )}

      <h3 className="note__title">{task.title}</h3>

      {task.description && (
        <p className="note__desc">{task.description}</p>
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

      {/* Drag handle visual cue */}
      <span className="note__drag-handle" aria-hidden title="Drag to reorder">
        ⠿
      </span>
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

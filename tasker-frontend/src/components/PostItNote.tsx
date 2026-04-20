import type { Task, Category, PaperSwatchId } from '../types';
import { PAPER_SWATCHES } from '../types';
import { formatDeadline, isOverdue, formatDuration, rotationFromId, jitterFromId } from '../utils';
import { WashiTape } from './WashiTape';

interface PostItNoteProps {
  task: Task;
  category: Category | undefined;
  index: number;
  leaving?: boolean;
  onClick: () => void;
}

const FALLBACK_SWATCH: PaperSwatchId = 'cream';

export function PostItNote({ task, category, index, leaving = false, onClick }: PostItNoteProps) {
  const swatchId = category?.swatchId ?? FALLBACK_SWATCH;
  const swatch = PAPER_SWATCHES.find(s => s.id === swatchId) ?? PAPER_SWATCHES[6];

  const rotation = rotationFromId(task.id);
  const translateX = jitterFromId(task.id, 1, 4);
  const tags = task.tags.slice(0, 3);

  return (
    <article
      className={`note${leaving ? ' note--leaving' : ''}`}
      style={{
        ['--paper' as string]: swatch.paper,
        ['--paper-edge' as string]: swatch.edge,
        ['--paper-ink' as string]: swatch.ink,
        ['--note-rot' as string]: `${rotation.toFixed(2)}deg`,
        ['--note-tx' as string]: `${translateX.toFixed(2)}px`,
        ['--note-i' as string]: index,
      }}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
      aria-label={`Open task: ${task.title}`}
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

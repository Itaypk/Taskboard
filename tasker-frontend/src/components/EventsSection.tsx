import { useState } from 'react';
import type { OneOffEvent } from '../types';
import { cancelEvent as cancelEventApi } from '../api';
import { formatTimeSlot } from '../utils';
import styles from './PlanDetails.module.css';

interface EventsSectionProps {
  events: OneOffEvent[];
  /** Called after an event is successfully cancelled, so the owner can drop it from its list. */
  onCancelled: (eventId: string) => void;
}

/**
 * List of one-off calendar events captured through /add that fall in the viewed week. Events whose
 * start is still in the future can be cancelled (soft-delete + calendar cancellation email); once an
 * event has started it's "triggered" and the button is hidden — its calendar copy is the user's now.
 */
export function EventsSection({ events, onCancelled }: EventsSectionProps) {
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  // Snapshot "now" once at mount — the future/past split only needs to be right when the drawer
  // opens, and reading the clock during render is an impurity the lint rule (rightly) rejects.
  const [now] = useState(() => Date.now());

  if (events.length === 0) return null;

  const cancel = (eventId: string) => {
    setCancellingId(eventId);
    cancelEventApi(eventId)
      .then(() => onCancelled(eventId))
      .catch(() => { /* the global error toast already surfaced it; leave the event in place */ })
      .finally(() => setCancellingId(null));
  };

  return (
    <section className={styles.section}>
      <h4 className={styles.sectionLabel}>Events ({events.length})</h4>
      <ul className={styles.eventList}>
        {events.map(event => {
          const cancellable = new Date(event.startsAt).getTime() > now;
          return (
            <li key={event.id} className={styles.eventItem}>
              <div className={styles.eventBody}>
                <span className={styles.eventTitle}>{event.title}</span>
                <span className={styles.eventMeta}>
                  <span>{formatTimeSlot(event.startsAt, event.endsAt)}</span>
                  {event.location && <span>📍 {event.location}</span>}
                </span>
              </div>
              {cancellable && (
                <button
                  type="button"
                  className={styles.eventCancelBtn}
                  onClick={() => cancel(event.id)}
                  disabled={cancellingId === event.id}
                  aria-label={`Cancel ${event.title}`}
                >
                  {cancellingId === event.id ? 'Cancelling…' : 'Cancel'}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

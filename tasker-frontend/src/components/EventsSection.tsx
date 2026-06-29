import type { OneOffEvent } from '../types';
import { formatTimeSlot } from '../utils';
import styles from './PlanDetails.module.css';

interface EventsSectionProps {
  events: OneOffEvent[];
}

/**
 * Read-only list of one-off calendar events captured through /add that fall in the viewed week.
 * Surfaced in the drawer next to the plan so the user can see what's already locked into the week
 * regardless of whether a plan has been finalized for it.
 */
export function EventsSection({ events }: EventsSectionProps) {
  if (events.length === 0) return null;
  return (
    <section className={styles.section}>
      <h4 className={styles.sectionLabel}>Events ({events.length})</h4>
      <ul className={styles.eventList}>
        {events.map(event => (
          <li key={event.id} className={styles.eventItem}>
            <span className={styles.eventTitle}>{event.title}</span>
            <span className={styles.eventMeta}>
              <span>{formatTimeSlot(event.startsAt, event.endsAt)}</span>
              {event.location && <span>📍 {event.location}</span>}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

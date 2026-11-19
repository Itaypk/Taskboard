import type { ReactNode } from 'react';
import type { SettingsSection } from '../../taskLink';

// Line icons drawn on a 24-unit grid; they take the text colour so the active section darkens with its label.
const PATHS: Record<SettingsSection, ReactNode> = {
  general: <><circle cx="12" cy="8" r="4" /><path d="M4 21c1-4 4-6 8-6s7 2 8 6" /></>,
  signin: <><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M17 6l3 3" /></>,
  planning: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></>,
  reminders: <><path d="M6 16V11a6 6 0 0112 0v5l2 2H4z" /><path d="M10 21h4" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M8 14l3 3 5-6" /></>,
  ai: <><path d="M12 3l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" /><path d="M19 15l1 2 2 1-2 1-1 2-1-2-2-1 2-1z" /></>,
  connections: <path d="M9 3v5M15 3v5M6 8h12v3a6 6 0 01-12 0zM12 17v4" />,
  data: <><path d="M3 7l9-4 9 4v10l-9 4-9-4z" /><path d="M3 7l9 4 9-4M12 11v10" /></>,
};

export function SectionIcon({ section, className }: { section: SettingsSection; className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {PATHS[section]}
    </svg>
  );
}

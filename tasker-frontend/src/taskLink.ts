// Interpretation of a task's `url` field. Most tasks hold an external `https://` link, but seeded
// tutorial tasks carry in-app deep links — an internal route (`/settings/<tab>`) or an action token
// (`app:clear-tutorial`). Classification is allowlist-based: anything unrecognized resolves to `null`
// so we never render or follow an unsafe scheme (`javascript:`, `data:`, …). Users can't create the
// internal/action forms (the API rejects non-http urls), so they only ever originate from the seeder.

export type TaskAction = 'clear-tutorial';

export type TaskLink =
  | { kind: 'external'; href: string }
  | { kind: 'internal'; to: string }
  | { kind: 'action'; action: TaskAction }
  | null;

const ACTIONS: Record<string, TaskAction> = {
  'app:clear-tutorial': 'clear-tutorial',
};

export function resolveTaskLink(url: string | null | undefined): TaskLink {
  if (!url) return null;
  const trimmed = url.trim();
  if (!trimmed) return null;
  if (/^https?:\/\//i.test(trimmed)) return { kind: 'external', href: trimmed };
  if (trimmed.startsWith('/')) return { kind: 'internal', to: trimmed };
  const action = ACTIONS[trimmed];
  if (action) return { kind: 'action', action };
  return null;
}

export type SettingsTab = 'general' | 'categories' | 'assistant';

const SETTINGS_TABS: readonly SettingsTab[] = ['general', 'categories', 'assistant'];

/** Maps a `/settings[/<tab>]` pathname to a settings tab; bare `/settings` or unknown → 'general'. */
export function settingsTabFromPath(pathname: string): SettingsTab {
  const segment = pathname.replace(/^\/settings\/?/, '').split('/')[0]?.toLowerCase();
  return SETTINGS_TABS.find(t => t === segment) ?? 'general';
}

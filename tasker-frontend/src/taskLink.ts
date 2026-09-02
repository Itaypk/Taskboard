// Interpretation of a task's `url` field. Most tasks hold an external `https://` link, but seeded
// tutorial tasks carry in-app deep links — an internal route (`/settings/<tab>`) or an action token
// (`app:clear-tutorial`). Classification is allowlist-based: anything unrecognized resolves to `null`
// so we never render or follow an unsafe scheme (`javascript:`, `data:`, …). Users can't create the
// internal/action forms (the API rejects non-http urls), so they only ever originate from the seeder.

import i18n from './i18n';

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

/**
 * Human-readable text for a resolved link, shown in the note footer next to the link icon.
 * External links show a prettified, truncated URL; internal/action links (tutorial-only) get a
 * fixed friendly name since their raw target ("/settings/general", "app:clear-tutorial") is meaningless
 * to a user.
 *
 * Those friendly names are translated. This is a plain helper rather than a component, so it reads
 * the i18n singleton directly — the same approach `App.tsx`'s `emptyMessageFor` uses. Every caller
 * renders inside a component that already calls `useTranslation()`, so the labels re-render when the
 * language changes. A URL is not translatable and stays as-is.
 */
export function linkLabel(link: TaskLink): string {
  if (!link) return '';
  switch (link.kind) {
    case 'external': return prettifyUrl(link.href);
    case 'internal': return i18n.t(link.to.startsWith('/settings') ? 'taskLink.openSettings' : 'taskLink.open');
    case 'action':
      switch (link.action) {
        case 'clear-tutorial': return i18n.t('taskLink.clearTutorial');
      }
  }
}

/** Strips scheme/`www.`/trailing slash and truncates so a long URL stays on one note line. */
function prettifyUrl(href: string, maxLen = 28): string {
  const stripped = href.replace(/^https?:\/\//i, '').replace(/^www\./i, '').replace(/\/+$/, '');
  return stripped.length > maxLen ? stripped.slice(0, maxLen - 1) + '…' : stripped;
}

export type SettingsTab = 'general' | 'assistant' | 'integrations';

const SETTINGS_TABS: readonly SettingsTab[] = ['general', 'assistant', 'integrations'];

/** Maps a `/settings[/<tab>]` pathname to a settings tab; bare `/settings` or unknown → 'general'. */
export function settingsTabFromPath(pathname: string): SettingsTab {
  const segment = pathname.replace(/^\/settings\/?/, '').split('/')[0]?.toLowerCase();
  return SETTINGS_TABS.find(t => t === segment) ?? 'general';
}

// Board settings has no deep-linked route (it's plain modal state, not URL-driven), so this tab
// type only governs which tab opens first — see BoardSettingsModal's `initialTab` prop.
export type BoardSettingsTab = 'general' | 'members' | 'labels';

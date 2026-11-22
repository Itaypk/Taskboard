// Interpretation of a task's `url` field. Most tasks hold an external `https://` link, but seeded
// tutorial tasks carry in-app deep links — an internal route (`/settings/<tab>`) or an action token
// (`app:clear-tutorial`, `app:open-planner`, `app:quick-add`, `app:save-account`). Classification is allowlist-based: anything
// unrecognized resolves to `null` so we never render or follow an unsafe scheme (`javascript:`,
// `data:`, …). Users can't create the internal/action forms (the API rejects non-http urls), so
// they only ever originate from the seeder.

import i18n from './i18n';

export type TaskAction = 'clear-tutorial' | 'open-planner' | 'quick-add' | 'save-account';

export type TaskLink =
  | { kind: 'external'; href: string }
  | { kind: 'internal'; to: string }
  | { kind: 'action'; action: TaskAction }
  | null;

const ACTIONS: Record<string, TaskAction> = {
  'app:clear-tutorial': 'clear-tutorial',
  'app:open-planner': 'open-planner',
  'app:quick-add': 'quick-add',
  'app:save-account': 'save-account',
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
 * the i18n singleton directly — the same approach `Board.tsx`'s `emptyMessageFor` uses. Every caller
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
        case 'open-planner': return i18n.t('taskLink.openPlanner');
        case 'quick-add': return i18n.t('taskLink.quickAdd');
        case 'save-account': return i18n.t('app.nudgeCta');
      }
  }
}

/** Strips scheme/`www.`/trailing slash and truncates so a long URL stays on one note line. */
function prettifyUrl(href: string, maxLen = 28): string {
  const stripped = href.replace(/^https?:\/\//i, '').replace(/^www\./i, '').replace(/\/+$/, '');
  return stripped.length > maxLen ? stripped.slice(0, maxLen - 1) + '…' : stripped;
}

/** The settings dialog's sections, in display order; each one is a `/settings/<section>` route. */
export const SETTINGS_SECTIONS = [
  'general', 'signin', 'planning', 'reminders', 'calendar', 'ai', 'connections', 'data',
] as const;
export type SettingsSection = (typeof SETTINGS_SECTIONS)[number];

// Routes from the old three-tab layout, still reachable from tutorial cards and bookmarks. The
// "assistant" tutorial card asks the user to tell the assistant about themselves, which now
// lives with the weekly planning session.
const LEGACY_SETTINGS_TABS: Record<string, SettingsSection> = {
  assistant: 'planning',
  integrations: 'calendar',
};

// The Telegram/Google link round-trips land on bare `/settings?<param>=<code>`, and the notice
// they carry is shown by the connected-accounts list on the sign-in section.
const SIGN_IN_PARAMS = ['telegramLink', 'googleLink'];

/**
 * Maps a `/settings[/<section>]` location to a section, or null for bare `/settings` (the
 * section list on a phone, the first section on a wider screen). An unknown section is null too.
 */
export function settingsSectionFromLocation(pathname: string, search = ''): SettingsSection | null {
  const segment = pathname.replace(/^\/settings\/?/, '').split('/')[0]?.toLowerCase() ?? '';
  const section = SETTINGS_SECTIONS.find(s => s === segment) ?? LEGACY_SETTINGS_TABS[segment];
  if (section) return section;
  const params = new URLSearchParams(search);
  return SIGN_IN_PARAMS.some(p => params.has(p)) ? 'signin' : null;
}

// Board settings has no deep-linked route (it's plain modal state, not URL-driven), so this tab
// type only governs which tab opens first — see BoardSettingsModal's `initialTab` prop.
export type BoardSettingsTab = 'general' | 'members' | 'labels';

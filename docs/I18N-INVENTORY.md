# Web UI i18n — component inventory

Tracks Phase 1 string-extraction progress (`docs/I18N.md`) component by component. This is a
living checklist, not a design doc — see `docs/I18N.md` for the *why* (library choice, locale
resolution, RTL plan, etc.). Update the status column as each component is extracted; re-run the
sizing script after a batch of PRs to catch drift.

## How this list was built

`tools/i18n-inventory.mjs` greps `tasker-frontend/src/**/*.tsx` for likely hardcoded copy (JSX
text nodes, `placeholder`/`title`/`aria-label`/`alt` attributes, and sentence-shaped string/template
literals) and counts hits per file. It's a heuristic, not a parser:

```bash
cd tasker-frontend && node ../tools/i18n-inventory.mjs
```

**Known blind spots** (verified by hand while building this list, not exhaustive):
- Template literals with an interpolated `${...}` are undercounted — the script conservatively
  treats any literal containing a stray `{`/`}` as "still code" to avoid false positives on JSX
  expressions, which also throws out real copy like `` `Edit tag ${tag.label}` `` (WashiTape) or
  `` `Cancel ${event.title}` `` (EventsSection). Grep those by hand before extracting.
  interpolation.
- Ternary-heavy JSX (`{cond ? (<div>...) : (<div>...)}`) occasionally leaks a code fragment into
  the "sample" column — the count for that file is still a fair signal, just ignore a garbled
  sample.
- Only scans `.tsx`; `src/api.ts` and `src/utils.ts` carry real user-facing copy too (see the
  dedicated section below) and are tracked separately.

Treat the **~Strings** column as "small / medium / large", not a precise count. Always read the
component before extracting — some hits are the same string repeated (e.g. "Loading…"), others
are near-duplicates worth consolidating into one key.

## Status legend

- ✅ **Done** — extracted into `src/locales/en/translation.json`, uses `useTranslation`/`t()`.
- ⬜ **Not started**
- ➖ **No copy** — purely structural/prop-driven, nothing to extract (verified by hand).

## Components

| Component | Status | ~Strings | Notes |
| --- | --- | ---: | --- |
| `src/auth/LoginPage.tsx` | ✅ Done | — | Phase-1 pilot extraction (landed in #122) |
| `src/NotFoundPage.tsx` | ✅ Done | — | Extracted this session |
| `src/components/ConfirmDialog.tsx` | ✅ Done | — | Extracted this session; default `confirmLabel` now resolved via `t()` instead of a hardcoded prop default |
| `src/components/ErrorToast.tsx` | ✅ Done | — | Extracted this session, paired with `src/api.ts` (see below) |
| `src/components/SettingsModal.tsx` | ✅ Done | — | Extracted this session; module-level `SETTINGS_TABS`/`DAYS_OF_WEEK` arrays now hold translation keys, resolved to labels inside the component (via `t()`) before being passed to `Tabs`/rendered in the day `<select>`s. Also fixed a pre-existing display bug: the "first day of week" help tip's `&quot;this week&quot;` was a literal HTML-entity string (never decoded, since it renders as plain React text) — now a real curly-quoted string in the catalog |
| `src/components/TaskDrawer.tsx` | ✅ Done | — | Extracted this session, including the tutorial-task banner copy and the module-level `validate()` field-error messages (resolved via the `i18n` singleton, like `utils.ts`/`api.ts`, since that function runs outside the component). Renamed several `.map(t => …)`/`.find(t => …)`/`.filter(t => …)` loop variables (all `Tag` objects, unrelated to translation) from `t` to `tag`/`existing` to stop them shadowing the `t()` translation function |
| `src/App.tsx` | ✅ Done | — | Extracted this session, including the module-level `emptyMessageFor()` (routed through the `i18n` singleton, like `utils.ts`) and the `buildContextMenuActions()` labels. The `error` state changed from a `string \| null` holding pre-rendered English text to a `boolean` — the message was always the same one string, so storing it as translated text at set-time would go stale across a language switch; it's now resolved via `t()` at render time. Renamed the one `.find(t => …)` inside `buildContextMenuActions` (a `Task`, unrelated to translation) to `pt` since that callback now calls `t()`; the ~20 other `Task`-typed `t` loop variables elsewhere in the file were left alone since none of those closures call `t()` |
| `src/components/BoardSettingsModal.tsx` | ✅ Done | — | Extracted this session, including the module-level `CONSENT` invite-warning string (moved from a top-level `const` into the catalog and resolved via `t()` inside `confirmCopy()`, which already had access to the hook). Mascot labels (`MASCOTS` in `src/mascots.ts`) are intentionally untouched — that module isn't in this inventory and is out of scope here |
| `src/components/WeeklyPlanDrawer.tsx` | ✅ Done | — | Extracted this session, including the module-level `relativeWeekLabel()` ("This week"/"Last week"/"{{count}} weeks ago"/"In {{count}} weeks" — routed through the `i18n` singleton, with proper `_one`/`_other` plural keys for the last two). `OverridePlanDialog` and `OverviewActions` are separate top-level functions (siblings of `WeeklyPlanDrawer`, not nested), so each got its own `useTranslation()` call. Renamed a local `const t = await fetchPlanningTranscript(id)` in `resume()` to `transcriptResult` since it shadowed the translation `t()`. The LLM-authored planning conversation messages themselves stay untranslated by this app-side catalog — they're generated server-side in the user's `preferred_language` per `docs/I18N.md` |
| `src/components/StatsModal.tsx` | ✅ Done | — | Extracted this session; completion-time row now uses count-based plural keys (`statsModal.days`/`statsModal.hours`) |
| `src/components/ConnectedAccounts.tsx` | ✅ Done | — | Extracted this session; provider labels and link notices now resolved via key lookup + `t()` |
| `src/auth/EmailLoginConfirmPage.tsx` | ✅ Done | — | Extracted this session |
| `src/auth/EmailVerifyConfirmPage.tsx` | ✅ Done | — | Extracted this session |
| `src/components/FeedbackModal.tsx` | ✅ Done | — | Extracted this session |
| `src/auth/InvitePage.tsx` | ✅ Done | — | Extracted this session; invitation body uses `Trans` for the two `<strong>` interpolations |
| `src/components/ScheduleTaskModal.tsx` | ✅ Done | — | Extracted this session |
| `src/components/MoveTaskModal.tsx` | ✅ Done | — | Extracted this session; `{{count}} members` now a proper plural key |
| `src/components/TagEditModal.tsx` | ✅ Done | — | Extracted this session |
| `src/components/ImportResultDialog.tsx` | ✅ Done | — | Extracted this session; `ERROR_COPY`/`EMAIL_SKIP_MESSAGES` now key-maps resolved via `t()`, keyed by the import error category (D6-style). The tasks/tags/categories summary sentence now uses three independently pluralized `t()` calls instead of `utils.plural()`, which had no other callers and was removed. The support-email `mailto:` subject stays hardcoded English (admin-facing output convention) |
| `src/components/AiUsageMeter.tsx` | ✅ Done | — | Extracted this session; the "resets in N days" caption is a proper plural key with two interpolations (`count`, `pct`) |
| `src/components/PostItNote.tsx` | ✅ Done | — | Extracted this session into a shared `taskCard.*` namespace, reused by `TaskLine.tsx` |
| `src/components/UserMenu.tsx` | ✅ Done | — | Extracted this session |
| `src/components/TaskLine.tsx` | ✅ Done | — | Extracted this session; reuses `taskCard.*` keys from `PostItNote.tsx` |
| `src/auth/PolicyPage.tsx` | ✅ Done | — | Extracted this session; nav chrome only — the legal *content* stays English (`docs/I18N.md` non-goal) |
| `src/components/BoardFilter.tsx` | ✅ Done | — | Extracted this session; the module-level `OPTIONS` array now stores translation keys instead of literal labels, resolved via `t()` in render |
| `src/components/MarkdownRenderer.tsx` | ✅ Done | — | Extracted this session; the ▲/▼ glyphs stay outside the catalog string, composed in JSX |
| `src/components/NoteEditor.tsx` | ✅ Done | — | Extracted this session, including the toolbar labels and the `window.prompt` copy |
| `src/components/PlanDetails.tsx` | ✅ Done | — | Extracted this session; "Started X · finished Y" and the "Tasks (N)" heading now go through `t()` |
| `src/components/BoardNameDialog.tsx` | ✅ Done | — | Extracted this session; `title`/`confirmLabel` remain caller-supplied props (owned by `BoardSettingsModal.tsx`, still pending) |
| `src/components/BrandBoard.tsx` | ✅ Done | — | Extracted this session; member count is now a proper plural key |
| `src/components/CategoryEditor.tsx` | ✅ Done | — | Extracted this session, including the seeded "New category" default label and the `In use by N task(s)` plural |
| `src/components/TagEditor.tsx` | ✅ Done | — | Extracted this session; renamed the `.map(t => …)` loop variable to `tag` to stop it shadowing `t()` |
| `src/components/UpdateBanner.tsx` | ✅ Done | — | Extracted this session |
| `src/components/ViewToggle.tsx` | ✅ Done | — | Extracted this session |
| `src/components/EventsSection.tsx` | ✅ Done | — | Extracted this session |
| `src/components/WashiTape.tsx` | ✅ Done | — | Extracted this session |
| `src/components/PaperSwatchPicker.tsx` | ✅ Done | — | Extracted this session |
| `src/components/SortMenu.tsx` | ✅ Done | — | Extracted this session; field labels now come from a `SORT_FIELD_LABEL_KEYS` map in the component rather than `SORT_OPTIONS.label` in `src/sort.ts`, keeping that module copy-free |
| `src/components/Autocomplete.tsx` | ➖ No copy | — | Re-checked by hand this session — no literal copy, only caller-supplied `placeholder`/option labels |
| `src/auth/AuthContext.tsx` | ➖ No copy | — | The one string ("useAuth must be used inside AuthProvider") is a dev-time programmer-error invariant, never shown to a real user |
| `src/components/ContextMenu.tsx` | ➖ No copy | — | Renders action labels passed in via props |
| `src/components/HelpTip.tsx` | ➖ No copy | — | `aria-label` is the caller-supplied `text` prop |
| `src/components/NoteEditorIcons.tsx` | ➖ No copy | — | Icon glyphs only |
| `src/components/Tabs.tsx` | ➖ No copy | — | Renders caller-supplied tab labels |
| `src/components/Toggle.tsx` | ➖ No copy | — | No text of its own |
| `src/components/Tooltip.tsx` | ➖ No copy | — | Renders caller-supplied children |

## Non-component modules with user-facing copy

Not `.tsx`, so outside the script's scan, but both are explicitly called out in `docs/I18N.md`:

| Module | Status | Notes |
| --- | --- | --- |
| `src/api.ts` | ✅ Done | `defaultMessageFor` (the D6 "client-side generic fallbacks") and the 401 `sessionExpired` message now call `i18n.t(...)` directly (plain module, no React context — uses the `i18n` singleton exported from `src/i18n/index.ts`, not the `useTranslation` hook) |
| `src/utils.ts` | ✅ Done | Extracted — `formatDeadline`/`formatRelative` now call the `i18n` singleton directly (plain module, like `api.ts`), with `utils.overdue`/`utils.hoursAgo`/`utils.daysAgo` as count-based keys (`_one`/`_other`) so i18next's `Intl.PluralRules`-backed pluralization applies once a language with different plural forms launches. The naive `plural(n, one, many)` helper had only one caller (`ImportResultDialog.tsx`); once that component moved to its own `_one`/`_other` keys, `plural()` was dead code and was deleted |

## Direction pass (Phase 2a) follow-up

String extraction is complete, but the RTL pass changed a few of the extracted strings and one
component contract. Worth knowing before touching these files again:

- **Arrows are no longer part of the copy.** Nine values had a directional glyph baked in
  ("← Back to sign in", "Go to my board →"); the glyph moved to `components/Arrow.tsx`, which
  mirrors with the document direction, and the catalog values are text only. When adding a link or
  button that needs an arrow, render `<Arrow direction="forward|back" />` beside the label rather
  than putting a character in the catalog. The `Settings → Assistant` breadcrumb inside
  `weeklyPlanDrawer.aiDisabledHint` deliberately stays in the string — it is mid-sentence prose and
  the translator should render it in their own direction.
- **`Tooltip`/`HelpTip` take a logical `side`.** The prop is now `'end' | 'start'` (reading order),
  not `'right' | 'left'`; `SettingsModal` is the only caller.
- **New CSS needs logical properties.** `src/i18n/logical-css.test.ts` fails on a physical
  `margin-left`, `padding-right`, `left`/`right` offset, or `float: left` anywhere under `src/`.
  If a declaration genuinely must not mirror (paper artwork with a baked light source, the mascot),
  add it to that test's exception list with a comment at the site saying why.
- **RTL is previewable in dev with `?uiLang=he`**, which forces direction and the (still English)
  catalog. Use it when changing layout.

## Summary

- **All ~44 tracked components/modules are done** (the untracked remainder is ➖ No copy):
  LoginPage pilot + NotFoundPage, ConfirmDialog, ErrorToast (earlier session); StatsModal,
  ConnectedAccounts, EmailLoginConfirmPage, EmailVerifyConfirmPage, FeedbackModal, InvitePage,
  `utils.ts` (PR #124); ScheduleTaskModal, MoveTaskModal, TagEditModal, ImportResultDialog,
  AiUsageMeter, PostItNote, UserMenu, TaskLine, PlanDetails (PR #125); PolicyPage, BoardFilter,
  MarkdownRenderer, NoteEditor, BoardNameDialog, BrandBoard, CategoryEditor, TagEditor,
  UpdateBanner, ViewToggle, EventsSection, WashiTape, PaperSwatchPicker, SortMenu (PR #126);
  SettingsModal (PR #127); TaskDrawer (PR #128); App.tsx (PR #129); BoardSettingsModal
  (PR #130); WeeklyPlanDrawer (this PR) — plus Autocomplete re-classified from "not started" to
  "no copy" after a hand check.
- **This closes out Phase 1 string extraction** (docs/I18N.md). What's *not* covered by this
  inventory, and remains for later phases: `src/mascots.ts`'s mascot labels (a small, untracked
  module discovered along the way — see the `BoardSettingsModal.tsx` note above); the shared
  `Intl` date/number formatting helper audit (D4); the RTL pass (D5); and the translated `he`/
  `ru`/`ar` catalogs themselves (D7, Phases 2–3). The LLM-authored planning conversation
  (`WeeklyPlanDrawer`'s chat messages) was never in scope for this catalog — it's generated
  server-side in the user's `preferred_language` already.
- `utils.ts`'s date helpers were already extracted, and every remaining caller is done too, so
  every `formatDeadline`/`formatRelative`/`formatDuration` call site in the app renders fully
  localized relative dates.
- New shared namespace: `taskCard.*` in the catalog holds strings common to `PostItNote.tsx` and
  `TaskLine.tsx` ("High priority", "In this week's plan", assignee/link/category labels) — reuse
  this namespace rather than re-adding the same copy under a new component key when touching
  either file again.
- `SortMenu.tsx`'s field labels moved out of `src/sort.ts` (`SORT_OPTIONS.label`) into a
  component-local `SORT_FIELD_LABEL_KEYS` map, so `sort.ts` — a pure comparator/ordering
  module — stays free of user-facing copy.

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
| `src/components/SettingsModal.tsx` | ⬜ Not started | 50 | Largest remaining surface — settings labels, connected-accounts strings, danger-zone copy |
| `src/components/TaskDrawer.tsx` | ⬜ Not started | 44 | Includes the seeded-tutorial-task banner copy |
| `src/App.tsx` | ⬜ Not started | 40 | Top-level shell: onboarding nudges, empty states, board switcher |
| `src/components/BoardSettingsModal.tsx` | ⬜ Not started | 28 | Board name/mascot, member management, invitations |
| `src/components/WeeklyPlanDrawer.tsx` | ⬜ Not started | 28 | Planning conversation UI chrome (not the LLM messages themselves) |
| `src/components/StatsModal.tsx` | ⬜ Not started | 15 | |
| `src/components/ConnectedAccounts.tsx` | ⬜ Not started | 14 | Link/unlink copy, conflict notices |
| `src/auth/EmailLoginConfirmPage.tsx` | ⬜ Not started | 12 | |
| `src/auth/EmailVerifyConfirmPage.tsx` | ⬜ Not started | 11 | |
| `src/components/FeedbackModal.tsx` | ⬜ Not started | 11 | |
| `src/auth/InvitePage.tsx` | ⬜ Not started | 10 | |
| `src/components/ScheduleTaskModal.tsx` | ⬜ Not started | 10 | |
| `src/components/MoveTaskModal.tsx` | ⬜ Not started | 9 | |
| `src/components/TagEditModal.tsx` | ⬜ Not started | 8 | |
| `src/components/ImportResultDialog.tsx` | ⬜ Not started | 7 | `ERROR_COPY`/`EMAIL_SKIP_MESSAGES` maps — good candidate to key by the import error `code` (D6) |
| `src/components/AiUsageMeter.tsx` | ⬜ Not started | 5 | |
| `src/components/PostItNote.tsx` | ⬜ Not started | 5 | Shares strings with `TaskLine.tsx` ("In this week's plan", "High priority") — extract once, reuse the key |
| `src/components/UserMenu.tsx` | ⬜ Not started | 5 | |
| `src/components/TaskLine.tsx` | ⬜ Not started | 4 | See `PostItNote.tsx` note |
| `src/auth/PolicyPage.tsx` | ⬜ Not started | 3 | Nav chrome only — the legal *content* stays English (`docs/I18N.md` non-goal) |
| `src/components/BoardFilter.tsx` | ⬜ Not started | 3 | |
| `src/components/MarkdownRenderer.tsx` | ⬜ Not started | 3 | |
| `src/components/NoteEditor.tsx` | ⬜ Not started | 3 | |
| `src/components/PlanDetails.tsx` | ⬜ Not started | 3 | |
| `src/components/BoardNameDialog.tsx` | ⬜ Not started | 2 | |
| `src/components/BrandBoard.tsx` | ⬜ Not started | 2 | |
| `src/components/CategoryEditor.tsx` | ⬜ Not started | 2 | |
| `src/components/TagEditor.tsx` | ⬜ Not started | 2 | |
| `src/components/UpdateBanner.tsx` | ⬜ Not started | 2 | |
| `src/components/ViewToggle.tsx` | ⬜ Not started | 2 | |
| `src/components/EventsSection.tsx` | ⬜ Not started | ~2 | Undercounted — see template-literal blind spot above (`Events (…)`, `Cancel {title}`) |
| `src/components/WashiTape.tsx` | ⬜ Not started | ~2 | Undercounted — "Edit tag"/"Remove tag" prefixes in interpolated `title`/`aria-label` |
| `src/components/PaperSwatchPicker.tsx` | ⬜ Not started | 1 | |
| `src/components/SortMenu.tsx` | ⬜ Not started | 1 | |
| `src/components/Autocomplete.tsx` | ⬜ Not started | 1 | Mostly a garbled sample from the script — re-check by hand |
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
| `src/utils.ts` | ⬜ Not started | Relative-date helpers hardcode English: `"Today"`, `"Tomorrow"`, `` `${n}d overdue` ``, `"just now"`, `` `${n}h ago` ``/`` `${n}d ago` ``. The `plural(n, one, many)` helper is naive English pluralization (`n === 1 ? one : many`) — will need `Intl.PluralRules` before Russian/Arabic ship (3 and 6 plural forms respectively), per D3/D4 in `docs/I18N.md` |

## Summary

- **4 of ~44 components/modules done** (LoginPage pilot + NotFoundPage, ConfirmDialog, ErrorToast
  from this session).
- Heaviest remaining lifts: `SettingsModal`, `TaskDrawer`, `App.tsx`, `BoardSettingsModal`,
  `WeeklyPlanDrawer` — all >25 strings, all central screens. Tackle these in dedicated PRs rather
  than folding them into a general pass.
- `utils.ts`'s date/plural helpers are worth doing early since almost every other component's
  extraction will end up calling into them.

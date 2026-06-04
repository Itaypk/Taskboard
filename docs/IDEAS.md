## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Planning sessions - issues
- Planning for the next week while there's an existing plan overrides the current plan. Expected behavior - it's a separate plan. _(Phase 1 done: the read side is now week-aware — `findCurrentPlan` returns the finalized plan for **the week containing today** (via `findPlanForWeek`), so finalizing next week no longer hides this week's plan. Carry-over/diff/previous-summary all resolve relative to the week being planned, not the globally-latest completed session. Phase 2 (UI to page through past/current/future plans) still pending.)_
- When planning for the next week, the assistant consider tasks that are part of the current week's plan. _(Fixed: `PlannerTaskSelector` now flags candidates that already have a slot in an earlier (e.g. current-week) plan with an `already_scheduled=DATE` annotation; the prompt tells the assistant to treat them as in-progress commitments and not re-propose them as new unless they've rolled over.)_
- ~~Planning and abandoning while there's an existing plan overrides the current - no way to roll back.~~ _(Fixed: starting/abandoning a session has no effect on the existing plan; the reschedule-count bump moved from session-start to finalize.)_
- New web UI does not offer a cancel/go back button, and the telegram can do with a `/cancel` as well. _(Web "Leave session" button added with a confirmation dialog; Telegram `/cancel` still open.)_

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Auto update (UI) - do we consider changes in tasks, or just additions/deletions?
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.

## UI - Tasks
- Better "mark as done"
- Drawer improvements (buttons are too dense, for example)
- Edit existing tags; add "description" to a tag (consider if needed)
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen
- Following up an assistant planning session, refresh the board (the assistant might've added tasks, changed tasks, etc.)
- ~~Top action buttons list: the addition of the "planning" button means that on most common mobile screens the buttons need a whole row.~~ _(Fixed: the "planning" (chat) and "weekly plan" buttons were unified into a single `WeeklyPlanDrawer` — the plan is the landing view, and Revise / Plan this week / Plan next week drill into the conversation. The header cluster dropped from 5 to 4 icons. The plan filter chip also shortens to "Week" on mobile so the filter row stops wrapping to two lines.)_
- Settings + Sign-out are low-frequency actions that still take top-level header slots. Fold them into an avatar/overflow (`⋯`) menu to slim the header cluster further (deferred from the plan-unification work).
- Filter chips can still wrap on very small screens even after the "Week" shortening. If it keeps bugging us, consider a segmented control or horizontally-scrollable chip row on mobile.

## Assistant - Mid-week response
- Re-use the existing "suggest_task" tool for a standalone /add command (would need more development for conversational adjustments - this will be a conversation). 
- When texting the assistant out of the blue, respond with the correct context.

## Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

## Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

## Production readiness
- All AI calls must be accounted for - user ID, token count; create a metric for observation, and apply rate limits per user.
- Application specific metrics and Grafana dashboard.

## Larger changes - consideration required
- Open source the application under AGPL
- Shift from being Telegram-centered to a more generic approach. Needs extra thinking for how to do it - a quick "get started"
  button is great, but without any sort of login the data is lost. Adding a local-only layer is complicated.
- WhatsApp as a communication channel support.
- Re-design the welcome page - it can look much better
- Multi-board and sharing support.
- Complete i18n support, including the web UI, welcome page, etc. Consider trimming the list of supported languages.
- Multi-modal support: the assistant can process images and voice messages. 

## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Planning sessions - issues
- Planning for the next week while there's an existing plan overrides the current plan. Expected behavior - it's a separate plan. _(Partly addressed: starting a session no longer hides the existing finalized plan — `findCurrentPlan` returns the latest **completed** plan, so an in-progress session never replaces it. True per-week separate plans are still not modeled: the board shows a single "current plan" = most recent completed.)_
- When planning for the next week, the assistant consider tasks that are part of the current week's plan.
- ~~Planning and abandoning while there's an existing plan overrides the current - no way to roll back.~~ _(Fixed: starting/abandoning a session has no effect on the existing plan; the reschedule-count bump moved from session-start to finalize.)_
- New web UI does not offer a cancel/go back button, and the telegram can do with a `/cancel` as well. _(Web "Leave session" button added with a confirmation dialog; Telegram `/cancel` still open.)_

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Translate the application
- Auto update (UI) - do we consider changes in tasks, or just additions/deletions?
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.

### UI - Tasks
- Better "mark as done"
- Drawer improvements (buttons are too dense, for example)
- Edit existing tags; add "description" to a tag (consider if needed)
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen

### Assistant - Mid-week response
- Re-use the existing "suggest_task" tool for a standalone /add command (would need more development for conversational adjustments - this will be a conversation). 
- When texting the assistant out of the blue, respond with the correct context.

### Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

### Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

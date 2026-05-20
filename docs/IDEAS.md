## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Open Questions

### Planning session date range alignment
Currently the planning period displayed to the user (in Telegram `/current` and the web drawer) starts on the day the session was initiated (`startedAt`), not on the user's configured `weekStartDay`. This means a session started on a Wednesday shows "Wed – Tue" rather than "Mon – Sun". Options:
- **Keep as-is**: simple, sessions are forward-looking from whenever you start them; `weekStartDay` only affects prompts.
- **Bind to week start**: when the orchestrator starts a session, compute the week-start date from the user's `weekStartDay` and use that as the display period start (even if the session started mid-week). Requires the period to be stored on the session entity or always re-derived from `weekStartDay` + `startedAt`.

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant is not very fast - need some "thinking..." or the such while it works on a response (added "Typing" indicator, but I'm not sure if that works)
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Tasks that are only relevant in the future - special field for that, do not display in default view
- Translations: first start with emails, then consider the application itself
- Auto update (UI) - do we consider changes in tasks, or just additions/deletions?

### UI - Tasks
- Better "mark as done"
- Drawer improvements
- Edit existing tags
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen

### Assistant - Mid-week response
- Rejected: new "add" command for adding tasks directly from Telegram (rejection reason: even for the most basic "add" we need a category; it would be either complicated or unhelpful). 
- When texting the assistant out of the blue, respond with the correct context.

### Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

### Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

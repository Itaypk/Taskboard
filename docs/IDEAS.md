## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.

## UI - Tasks
- Better "mark as done"
- Drawer improvements (buttons are too dense, for example)
- Edit existing tags; add "description" to a tag (consider if needed)
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen
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

## Small features
- User stats, available on the web and through a /stats command:
  - When did you join
  - How many tasks you have vs. completed
  - Average task completion per week, average new tasks per week 
  - How much time it takes you on average to complete a task, etc.
  - How many planning sessions did you have

## Larger changes - consideration required
- Open source the application under AGPL
- Shift from being Telegram-centered to a more generic approach. Needs extra thinking for how to do it - a quick "get started"
  button is great, but without any sort of login the data is lost. Adding a local-only layer is complicated.
- WhatsApp as a communication channel support.
- Re-design the welcome page - it can look much better
- Multi-board and sharing support.
- Complete i18n support, including the web UI, welcome page, etc. Consider trimming the list of supported languages.
- Multi-modal support: the assistant can process images and voice messages. 

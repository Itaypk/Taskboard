## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Translate the application
- Auto update (UI) - do we consider changes in tasks, or just additions/deletions?

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

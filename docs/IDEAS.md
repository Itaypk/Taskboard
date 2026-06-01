## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Technical Debt

### Ad-hoc tasks in the agreed plan have no backlog identity
The `submit_plan` tool allows the LLM to include tasks without a `task_id` (ad-hoc items the user mentions during the conversation that aren't in the backlog). `AgreedPlanTask.taskId` is nullable to support this. In practice this creates an awkward in-between state: the planned slot is stored in `planned_task` (with `backlogTaskId = null`), but the task has no `BacklogTaskEntity`, so it never appears on the board, is silently dropped from `GET /plans/current` (the controller skips rows with no linked backlog task), and cannot receive a `lastScheduledInSessionId` stamp. The right fix is to either (a) always require the LLM to create a real backlog entry before scheduling, or (b) auto-create a minimal `BacklogTaskEntity` at plan-submission time for any ad-hoc task so it lands on the board like any other planned item.

## Small Improvements and Concerns
- Client side error messages - more friendly? error reference? email support?
- AI assistant should be aware of the notification delivery methods (e.g., invitation emails, nothing)
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Translate the application
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

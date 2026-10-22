package dev.itayp.tasker.capture

import java.util.UUID

/** What a capture saved: the titles of everything created, and the ids of the tasks among them. */
data class SavedCapture(
    val boardId: UUID,
    val titles: List<String>,
    val taskIds: List<UUID>,
    /** An event was saved, but no calendar invitation goes out for it (no verified email, or invites off). */
    val invitesSkipped: Boolean,
)

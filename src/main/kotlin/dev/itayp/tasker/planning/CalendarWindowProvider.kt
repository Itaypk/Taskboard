package dev.itayp.tasker.planning

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Returns a human-readable description of the user's calendar across the planning window.
 * Real implementations will pull from Google Calendar; for now we return a placeholder so
 * the rest of the assistant flow can be exercised end-to-end.
 */
interface CalendarWindowProvider {
    fun describeWindow(userId: UUID, from: Instant, to: Instant): String
}

@Component
class StubCalendarWindowProvider : CalendarWindowProvider {
    override fun describeWindow(userId: UUID, from: Instant, to: Instant): String =
        "User has not connected their calendar; assume no fixed commitments are known."
}

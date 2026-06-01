package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.invitation.CalendarEvent
import dev.itayp.tasker.channel.email.invitation.CalendarInvitationComposer
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.Locale

@Component
class PlanInviteDispatcher(
    private val calendarInvitationComposer: CalendarInvitationComposer,
) {
    private val log = LoggerFactory.getLogger(PlanInviteDispatcher::class.java)

    @Async
    fun dispatch(userEmail: String, organizerEmail: String, organizerName: String, plan: AgreedPlan, locale: Locale) {
        for (task in plan.tasks) {
            for (slot in task.slots) {
                runCatching {
                    val start = OffsetDateTime.parse(slot.startIso).toZonedDateTime()
                    val end = OffsetDateTime.parse(slot.endIso).toZonedDateTime()
                    // Stable UID so calendar clients deduplicate re-sends of the same slot.
                    val uid = "${task.taskId ?: task.title.hashCode()}-${slot.startIso}"
                    calendarInvitationComposer.sendInvitation(
                        to = listOf(userEmail),
                        event = CalendarEvent(
                            uid = uid,
                            title = task.title,
                            description = task.notes,
                            start = start,
                            end = end,
                            organizerEmail = organizerEmail,
                            organizerName = organizerName,
                            attendeeEmails = listOf(userEmail),
                            locale = locale,
                        ),
                    )
                }.onFailure { e ->
                    log.error("Failed to send calendar invite for task '{}': {}", task.title, e.message)
                }
            }
        }
    }

    @Async
    fun dispatchCancellations(
        userEmail: String,
        organizerEmail: String,
        organizerName: String,
        tasks: List<AgreedPlanTask>,
        locale: Locale,
    ) {
        for (task in tasks) {
            for (slot in task.slots) {
                runCatching {
                    val start = OffsetDateTime.parse(slot.startIso).toZonedDateTime()
                    val end = OffsetDateTime.parse(slot.endIso).toZonedDateTime()
                    val uid = "${task.taskId ?: task.title.hashCode()}-${slot.startIso}"
                    calendarInvitationComposer.sendCancellation(
                        to = listOf(userEmail),
                        event = CalendarEvent(
                            uid = uid,
                            title = task.title,
                            description = task.notes,
                            start = start,
                            end = end,
                            organizerEmail = organizerEmail,
                            organizerName = organizerName,
                            attendeeEmails = listOf(userEmail),
                            locale = locale,
                        ),
                    )
                }.onFailure { e ->
                    log.error("Failed to send cancellation for task '{}': {}", task.title, e.message)
                }
            }
        }
    }
}

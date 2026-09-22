package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.SmtpEmailChannel.Companion.mask
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

    /** Sends fresh invitations (SEQUENCE 0) for newly-added slots. */
    @Async
    fun dispatch(userEmail: String, organizerEmail: String, organizerName: String, plan: AgreedPlan, locale: Locale) {
        sendInvites(userEmail, organizerEmail, organizerName, plan, locale, sequence = 0)
    }

    /**
     * Re-sends invitations for slots whose details changed at the same start time (label/title/notes
     * or end time). The shared UID plus a bumped SEQUENCE makes calendar clients update the existing
     * event in place rather than create a duplicate.
     */
    @Async
    fun dispatchUpdates(userEmail: String, organizerEmail: String, organizerName: String, plan: AgreedPlan, locale: Locale) {
        sendInvites(userEmail, organizerEmail, organizerName, plan, locale, sequence = 1)
    }

    private fun sendInvites(
        userEmail: String,
        organizerEmail: String,
        organizerName: String,
        plan: AgreedPlan,
        locale: Locale,
        sequence: Int,
    ) {
        for ((taskId, title, slots, notes) in plan.tasks) {
            for ((startIso, endIso) in slots) {
                runCatching {
                    val start = OffsetDateTime.parse(startIso).toZonedDateTime()
                    val end = OffsetDateTime.parse(endIso).toZonedDateTime()
                    // Stable UID so calendar clients deduplicate re-sends of the same slot.
                    val uid = "$taskId-$startIso"
                    calendarInvitationComposer.sendInvitation(
                        to = listOf(userEmail),
                        event = CalendarEvent(
                            uid = uid,
                            title = title,
                            description = notes,
                            start = start,
                            end = end,
                            organizerEmail = organizerEmail,
                            organizerName = organizerName,
                            attendeeEmails = listOf(userEmail),
                            locale = locale,
                            sequence = sequence,
                        ),
                    )
                }.onFailure { e ->
                    log.error("Failed to send calendar invite for task {}: {}", taskId, e.message, e)
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
        for ((taskId, title, slots, notes) in tasks) {
            for ((startIso, endIso) in slots) {
                runCatching {
                    val start = OffsetDateTime.parse(startIso).toZonedDateTime()
                    val end = OffsetDateTime.parse(endIso).toZonedDateTime()
                    val uid = "$taskId-$startIso"
                    calendarInvitationComposer.sendCancellation(
                        to = listOf(userEmail),
                        event = CalendarEvent(
                            uid = uid,
                            title = title,
                            description = notes,
                            start = start,
                            end = end,
                            organizerEmail = organizerEmail,
                            organizerName = organizerName,
                            attendeeEmails = listOf(userEmail),
                            locale = locale,
                        ),
                    )
                }.onFailure { e ->
                    log.error("Failed to send cancellation for task {}: {}", taskId, e.message, e)
                }
            }
        }
    }
}

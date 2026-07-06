package dev.itayp.tasker.oneoff

import dev.itayp.tasker.channel.email.invitation.CalendarEvent
import dev.itayp.tasker.channel.email.invitation.CalendarInvitationComposer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.util.Locale

/**
 * Off-thread bridge to [CalendarInvitationComposer] for one-off events. Mirrors
 * `PlanInviteDispatcher`: persistence commits first, then this fires; a failure here is logged
 * but never rolls back the saved event row (the user can re-send manually in a future phase).
 */
@Component
class OneOffEventInviteDispatcher(
    private val calendarInvitationComposer: CalendarInvitationComposer,
) {
    private val log = LoggerFactory.getLogger(OneOffEventInviteDispatcher::class.java)

    data class Invite(
        val event: OneOffEvent,
        val userEmail: String,
        val organizerEmail: String,
        val organizerName: String,
        val locale: Locale,
        val zone: ZoneId,
    )

    @Async
    fun dispatch(invites: List<Invite>) {
        for (invite in invites) {
            runCatching {
                calendarInvitationComposer.sendInvitation(
                    to = listOf(invite.userEmail),
                    event = invite.toCalendarEvent(sequence = 0),
                )
            }.onFailure { e ->
                log.error("Failed to send one-off event invite for event {}: {}", invite.event.id, e.message)
            }
        }
    }

    /**
     * Sends `METHOD:CANCEL` emails for cancelled events. Reuses the event's stable `icalUid` so the
     * user's calendar client matches the cancellation to the block it previously accepted. Fired
     * after the cancellation commits; a failure here is logged, never rolled back.
     */
    @Async
    fun dispatchCancellations(invites: List<Invite>) {
        for (invite in invites) {
            runCatching {
                calendarInvitationComposer.sendCancellation(
                    to = listOf(invite.userEmail),
                    event = invite.toCalendarEvent(sequence = 1),
                )
            }.onFailure { e ->
                log.error("Failed to send one-off event cancellation for event {}: {}", invite.event.id, e.message)
            }
        }
    }

    private fun Invite.toCalendarEvent(sequence: Int) = CalendarEvent(
        uid = event.icalUid,
        title = event.title,
        description = event.notes,
        start = event.startsAt.atZone(zone),
        end = event.endsAt.atZone(zone),
        location = event.location,
        organizerEmail = organizerEmail,
        organizerName = organizerName,
        attendeeEmails = listOf(userEmail),
        locale = locale,
        sequence = sequence,
    )
}

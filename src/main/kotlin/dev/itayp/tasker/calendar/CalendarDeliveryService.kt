package dev.itayp.tasker.calendar

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.invitation.ICalSequence
import dev.itayp.tasker.oneoff.OneOffEventInviteDispatcher
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.planning.PlanInviteDispatcher
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** The calendar card in Settings: the chosen delivery, whether invitations are possible, and the feed link. */
data class CalendarDeliveryState(
    val delivery: CalendarDelivery,
    /** Invitations need email on this instance and a verified address. */
    val emailAvailable: Boolean,
    /** Present while the delivery is [CalendarDelivery.FEED]; the link outlives a switch away, but isn't shown then. */
    val feed: CalendarFeedLink?,
)

/** What switching to a delivery would do, for the confirmation the UI shows first. */
data class CalendarDeliverySwitchPreview(
    /** Upcoming blocks whose invitations would be cancelled. */
    val invitationsToCancel: Int,
    /** Upcoming blocks that would get an invitation. */
    val invitationsToSend: Int,
)

/** A switch to [CalendarDelivery.EMAIL] when invitations can't be delivered. Maps to 400. */
class CalendarInvitesUnavailableException :
    IllegalStateException("Calendar invitations need email on this instance and a verified email address")

/**
 * Owns the user's calendar delivery choice (GitHub issue #346) and what a switch does to the
 * blocks already on their calendar:
 *
 * - invitations → feed: the upcoming invited blocks are cancelled, or they'd show twice;
 * - anything → invitations: the upcoming blocks are invited (same UID and a higher `SEQUENCE`
 *   than any earlier invitation, so a block invited before updates rather than duplicates);
 * - invitations → off: nothing, as the old toggle did (the user may want what's there to stay);
 * - feed → anything: the feed goes empty on the calendar app's next poll.
 *
 * Only *upcoming* blocks are touched; past ones stay where they are.
 */
@Service
class CalendarDeliveryService(
    private val userSettingsService: UserSettingsService,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val calendarFeedService: CalendarFeedService,
    private val calendarFeedContent: CalendarFeedContent,
    private val planInviteDispatcher: PlanInviteDispatcher,
    private val oneOffEventInviteDispatcher: OneOffEventInviteDispatcher,
    private val emailProperties: EmailProperties,
    private val iCalSequence: ICalSequence,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(CalendarDeliveryService::class.java)

    fun getState(userId: UUID): CalendarDeliveryState {
        val delivery = userSettingsService.getOrCreate(userId).calendarDelivery
        return CalendarDeliveryState(
            delivery = delivery,
            emailAvailable = inviteDeliveryResolver.resolveEmailTarget(userId) != null,
            // Created on demand: an imported FEED setting arrives without a link.
            feed = if (delivery == CalendarDelivery.FEED) calendarFeedService.getOrCreateLink(userId) else null,
        )
    }

    fun preview(userId: UUID, target: CalendarDelivery): CalendarDeliverySwitchPreview {
        val current = userSettingsService.getOrCreate(userId).calendarDelivery
        if (current == target) return CalendarDeliverySwitchPreview(0, 0)
        val count by lazy { upcoming(userId).blockCount() }
        return CalendarDeliverySwitchPreview(
            invitationsToCancel = if (current == CalendarDelivery.EMAIL && target == CalendarDelivery.FEED) count else 0,
            invitationsToSend = if (target == CalendarDelivery.EMAIL) count else 0,
        )
    }

    fun change(userId: UUID, target: CalendarDelivery): CalendarDeliveryState {
        val current = userSettingsService.getOrCreate(userId).calendarDelivery
        if (current == target) return getState(userId)
        val emailTarget = inviteDeliveryResolver.resolveEmailTarget(userId)
        if (target == CalendarDelivery.EMAIL && emailTarget == null) throw CalendarInvitesUnavailableException()

        userSettingsService.setCalendarDelivery(userId, target)
        if (target == CalendarDelivery.FEED) calendarFeedService.getOrCreateLink(userId)
        log.info("Calendar delivery for user {} changed from {} to {}", userId, current, target)

        when {
            current == CalendarDelivery.EMAIL && target == CalendarDelivery.FEED && emailTarget != null ->
                cancelInvitations(userId, emailTarget)
            target == CalendarDelivery.EMAIL && emailTarget != null ->
                sendInvitations(userId, emailTarget)
        }
        return getState(userId)
    }

    private fun cancelInvitations(userId: UUID, to: InviteDeliveryResolver.EmailContext) {
        val upcoming = upcoming(userId)
        if (upcoming.isEmpty) return
        log.debug("Cancelling {} upcoming invitation(s) for user {}", upcoming.blockCount(), userId)
        val sequence = iCalSequence.next()
        if (upcoming.planTasks.isNotEmpty()) {
            planInviteDispatcher.dispatchCancellations(
                to.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                upcoming.planTasks, to.locale, sequence,
            )
        }
        if (upcoming.oneOffEvents.isNotEmpty()) {
            oneOffEventInviteDispatcher.dispatchCancellations(oneOffInvites(userId, upcoming, to, sequence))
        }
    }

    private fun sendInvitations(userId: UUID, to: InviteDeliveryResolver.EmailContext) {
        val upcoming = upcoming(userId)
        if (upcoming.isEmpty) return
        log.debug("Sending {} invitation(s) for upcoming blocks of user {}", upcoming.blockCount(), userId)
        val sequence = iCalSequence.next()
        if (upcoming.planTasks.isNotEmpty()) {
            planInviteDispatcher.dispatch(
                to.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                AgreedPlan(upcoming.planTasks, summary = ""), to.locale, sequence,
            )
        }
        if (upcoming.oneOffEvents.isNotEmpty()) {
            oneOffEventInviteDispatcher.dispatch(oneOffInvites(userId, upcoming, to, sequence))
        }
    }

    private fun oneOffInvites(
        userId: UUID,
        content: CalendarContent,
        to: InviteDeliveryResolver.EmailContext,
        sequence: Int,
    ): List<OneOffEventInviteDispatcher.Invite> {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        return content.oneOffEvents.map { event ->
            OneOffEventInviteDispatcher.Invite(
                event = event,
                userEmail = to.email,
                organizerEmail = emailProperties.scheduling.from,
                organizerName = emailProperties.scheduling.fromName,
                locale = to.locale,
                zone = zone,
                sequence = sequence,
            )
        }
    }

    /** Blocks that haven't started yet: the ones a switch moves between invitations and the feed. */
    private fun upcoming(userId: UUID): CalendarContent {
        val now = clock.instant()
        val content = calendarFeedContent.collect(userId, now, now)
        return CalendarContent(
            planTasks = content.planTasks.mapNotNull { task ->
                val slots = task.slots.filter { OffsetDateTime.parse(it.startIso).toInstant().isAfter(now) }
                if (slots.isEmpty()) null else task.copy(slots = slots)
            },
            oneOffEvents = content.oneOffEvents.filter { it.startsAt.isAfter(now) },
        )
    }

    private fun CalendarContent.blockCount(): Int = planTasks.sumOf { it.slots.size } + oneOffEvents.size
}

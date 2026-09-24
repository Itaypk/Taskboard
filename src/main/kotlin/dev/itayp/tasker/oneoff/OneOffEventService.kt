package dev.itayp.tasker.oneoff

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.invitation.ICalSequence
import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.OneOffEventEntity
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.repository.OneOffEventRepository
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Public entry point for creating one-off calendar events from the /add flow. Persistence is
 * delegated to [OneOffEventWriter] so its `@Transactional` boundary runs through a Spring proxy
 * (self-invocation would bypass it); invite dispatch fires *after* the writer's transaction
 * commits, so a delivery failure never rolls back the saved rows.
 */
@Service
class OneOffEventService(
    private val writer: OneOffEventWriter,
    private val repository: OneOffEventRepository,
    private val boardCrypto: BoardCryptoService,
    private val userSettingsService: UserSettingsService,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val inviteDispatcher: OneOffEventInviteDispatcher,
    private val emailProperties: EmailProperties,
    private val iCalSequence: ICalSequence,
) {
    private val log = LoggerFactory.getLogger(OneOffEventService::class.java)

    fun createEvents(userId: UUID, boardId: UUID, drafts: List<OneOffEventDraft>): CreatedEvents {
        if (drafts.isEmpty()) return CreatedEvents(emptyList(), invitesScheduled = false)
        val saved = writer.persist(userId, boardId, drafts)
        val scheduled = scheduleInvites(userId, saved)
        return CreatedEvents(saved, invitesScheduled = scheduled)
    }

    /**
     * Cancels a not-yet-started one-off event: soft-deletes it (`cancelled_at`) and — when the
     * write actually flips a live row — dispatches a `METHOD:CANCEL` calendar email so the block
     * disappears from the user's calendar. The write runs in [OneOffEventWriter.cancel] (its own
     * proxied transaction); dispatch fires *after* that commits, mirroring the create path, so an
     * email failure never rolls back the cancellation.
     */
    fun cancelEvent(userId: UUID, eventId: UUID): CancelOutcome {
        val outcome = writer.cancel(userId, eventId)
        if (outcome is CancelOutcome.Cancelled) dispatchCancellation(userId, outcome.event)
        return outcome
    }

    private fun dispatchCancellation(userId: UUID, event: OneOffEvent) {
        val ctx = inviteDeliveryResolver.resolveEmailContext(userId)
        if (ctx == null) {
            log.debug("Skipping one-off event cancellation email for user {}: no eligible email channel", userId)
            return
        }
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        inviteDispatcher.dispatchCancellations(
            listOf(
                OneOffEventInviteDispatcher.Invite(
                    event = event,
                    userEmail = ctx.email,
                    organizerEmail = emailProperties.scheduling.from,
                    organizerName = emailProperties.scheduling.fromName,
                    locale = ctx.locale,
                    zone = zone,
                    sequence = iCalSequence.next(),
                ),
            ),
        )
    }

    @Transactional(readOnly = true)
    fun listForWeek(userId: UUID, weekStart: Instant, weekEnd: Instant): List<OneOffEvent> =
        repository
            .findAllByUserIdAndStartsAtBetweenAndCancelledAtIsNullOrderByStartsAtAsc(userId, weekStart, weekEnd)
            .map { it.toDomain(boardCrypto) }

    /**
     * Events whose start falls inside the user's local ISO week starting at [weekStart]. Resolves
     * the user's timezone so a Sun–Sat window in their local calendar maps to the right instant
     * range — important when one of those local days straddles UTC midnight.
     */
    @Transactional(readOnly = true)
    fun listForLocalWeek(userId: UUID, weekStart: LocalDate): List<OneOffEvent> {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        val from = weekStart.atStartOfDay(zone).toInstant()
        val to = weekStart.plusDays(7).atStartOfDay(zone).toInstant()
        return listForWeek(userId, from, to)
    }

    private fun scheduleInvites(userId: UUID, events: List<OneOffEvent>): Boolean {
        if (events.isEmpty()) return false
        val ctx = inviteDeliveryResolver.resolveEmailContext(userId)
        if (ctx == null) {
            log.debug("Skipping one-off event invites for user {}: no eligible email channel", userId)
            return false
        }
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        val sequence = iCalSequence.next()
        val invites = events.map { event ->
            OneOffEventInviteDispatcher.Invite(
                event = event,
                userEmail = ctx.email,
                organizerEmail = emailProperties.scheduling.from,
                organizerName = emailProperties.scheduling.fromName,
                locale = ctx.locale,
                zone = zone,
                sequence = sequence,
            )
        }
        inviteDispatcher.dispatch(invites)
        return true
    }
}

/** Outcome of [OneOffEventService.createEvents]: the persisted rows plus whether invites were scheduled. */
data class CreatedEvents(val events: List<OneOffEvent>, val invitesScheduled: Boolean)

/** Result of a cancel attempt — the controller maps each case to an HTTP status. */
sealed interface CancelOutcome {
    /** No such event owned by this user. */
    data object NotFound : CancelOutcome

    /** Already cancelled — idempotent no-op (no second cancellation email). */
    data object AlreadyCancelled : CancelOutcome

    /** The event has already started; a triggered event can't be cancelled. */
    data object AlreadyStarted : CancelOutcome

    /** The row was flipped to cancelled; [event] is the just-cancelled plaintext view. */
    data class Cancelled(val event: OneOffEvent) : CancelOutcome
}

@Service
class OneOffEventWriter(
    private val repository: OneOffEventRepository,
    private val boardMembershipService: BoardMembershipService,
    private val boardCrypto: BoardCryptoService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OneOffEventWriter::class.java)

    @Transactional
    fun persist(userId: UUID, boardId: UUID, drafts: List<OneOffEventDraft>): List<OneOffEvent> {
        boardMembershipService.requireMember(userId, boardId)
        val now = Instant.now(clock)
        val entities = drafts.map { draft ->
            require(!draft.endsAt.isBefore(draft.startsAt)) {
                "Event end ${draft.endsAt} is before start ${draft.startsAt}"
            }
            OneOffEventEntity().apply {
                this.userId = userId
                this.boardId = boardId
                this.title = boardCrypto.encrypt(boardId, draft.title)
                this.location = boardCrypto.encrypt(boardId, draft.location)
                this.notes = boardCrypto.encrypt(boardId, draft.notes)
                this.startsAt = draft.startsAt
                this.endsAt = draft.endsAt
                this.icalUid = UUID.randomUUID().toString()
                this.createdAt = now
            }
        }
        val persisted = repository.saveAll(entities)
        log.info("Created {} one-off event(s) on board {}", persisted.size, boardId)
        return persisted.map { it.toDomain(boardCrypto) }
    }

    /**
     * Loads, guards, and soft-cancels the event in a single transaction so the start-time check and
     * the write can't race a concurrent cancel. Returns a [CancelOutcome] the caller inspects to
     * decide whether to dispatch the cancellation email.
     */
    @Transactional
    fun cancel(userId: UUID, eventId: UUID): CancelOutcome {
        val entity = repository.findByIdAndUserId(eventId, userId) ?: return CancelOutcome.NotFound
        if (entity.cancelledAt != null) return CancelOutcome.AlreadyCancelled
        val now = Instant.now(clock)
        if (!requireNotNull(entity.startsAt).isAfter(now)) return CancelOutcome.AlreadyStarted
        entity.cancelledAt = now
        val saved = repository.save(entity)
        log.info("Cancelled one-off event {}", eventId)
        return CancelOutcome.Cancelled(saved.toDomain(boardCrypto))
    }
}

internal fun OneOffEventEntity.toDomain(boardCrypto: BoardCryptoService): OneOffEvent {
    val boardId = requireNotNull(this.boardId) { "OneOffEventEntity board_id is null" }
    return OneOffEvent(
        id = requireNotNull(this.id),
        userId = requireNotNull(this.userId),
        boardId = boardId,
        title = boardCrypto.decrypt(boardId, this.title) ?: "",
        startsAt = requireNotNull(this.startsAt),
        endsAt = requireNotNull(this.endsAt),
        location = boardCrypto.decrypt(boardId, this.location),
        notes = boardCrypto.decrypt(boardId, this.notes),
        icalUid = requireNotNull(this.icalUid),
        cancelledAt = this.cancelledAt,
    )
}

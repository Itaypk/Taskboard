package dev.itayp.tasker.oneoff

import dev.itayp.tasker.channel.email.EmailProperties
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
) {
    private val log = LoggerFactory.getLogger(OneOffEventService::class.java)

    fun createEvents(userId: UUID, boardId: UUID, drafts: List<OneOffEventDraft>): CreatedEvents {
        if (drafts.isEmpty()) return CreatedEvents(emptyList(), invitesScheduled = false)
        val saved = writer.persist(userId, boardId, drafts)
        val scheduled = scheduleInvites(userId, saved)
        return CreatedEvents(saved, invitesScheduled = scheduled)
    }

    @Transactional(readOnly = true)
    fun listForWeek(userId: UUID, weekStart: Instant, weekEnd: Instant): List<OneOffEvent> =
        repository
            .findAllByUserIdAndStartsAtBetweenAndCancelledAtIsNullOrderByStartsAtAsc(userId, weekStart, weekEnd)
            .map { it.toDomain(boardCrypto) }

    private fun scheduleInvites(userId: UUID, events: List<OneOffEvent>): Boolean {
        if (events.isEmpty()) return false
        val ctx = inviteDeliveryResolver.resolveEmailContext(userId)
        if (ctx == null) {
            log.debug("Skipping one-off event invites for user {}: no eligible email channel", userId)
            return false
        }
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        val invites = events.map { event ->
            OneOffEventInviteDispatcher.Invite(
                event = event,
                userEmail = ctx.email,
                organizerEmail = emailProperties.scheduling.from,
                organizerName = emailProperties.scheduling.fromName,
                locale = ctx.locale,
                zone = zone,
            )
        }
        inviteDispatcher.dispatch(invites)
        return true
    }
}

/** Outcome of [OneOffEventService.createEvents]: the persisted rows plus whether invites were scheduled. */
data class CreatedEvents(val events: List<OneOffEvent>, val invitesScheduled: Boolean)

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

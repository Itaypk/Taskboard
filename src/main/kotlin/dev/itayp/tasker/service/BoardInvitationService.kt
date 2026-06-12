package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.BoardInvitationEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.AcceptedInvitation
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.InvitationPreview
import dev.itayp.tasker.model.PendingInvitation
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.BoardInvitationRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Board invitations — an email capability token modelled on [EmailLoginService]: single-use, TTL'd,
 * rate-limited, storing only the email hash. The acceptor need not log in with the invited address
 * (the token is the authorization, docs/BOARD-SHARING-PHASE2.md Decision 3); the accept screen shows
 * which account they're joining as. Membership *is* the access grant — no board-DEK hand-off, because
 * keys are server-side under the app KEK.
 */
@Service
class BoardInvitationService(
    private val invitationRepository: BoardInvitationRepository,
    private val boardMembershipRepository: BoardMembershipRepository,
    private val boardMembershipService: BoardMembershipService,
    private val authIdentityRepository: AuthIdentityRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val boardRepository: BoardRepository,
    private val boardCrypto: BoardCryptoService,
    private val displayNameResolver: MemberDisplayNameResolver,
    @Qualifier("authEmailChannel") private val outboundChannel: OutboundChannel,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val messageSource: MessageSource,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(BoardInvitationService::class.java)

    /**
     * Sends an invitation to [email] for [boardId]. Owner-only; the inviter must hold a real login
     * method (demo users can't drag strangers into an ephemeral board). Guards member cap, duplicate
     * membership, and per-board / per-inviter daily rate limits; a re-invite revokes the prior pending one.
     */
    @Transactional
    fun invite(inviterUserId: UUID, boardId: UUID, email: String): PendingInvitation {
        requireOwner(inviterUserId, boardId)
        if (!authIdentityRepository.existsByUserId(inviterUserId)) throw InviterNotEligibleException()

        val now = clock.instant()
        if (invitationRepository.countByBoardIdAndCreatedAtAfter(boardId, now.minus(RATE_WINDOW)) >= BOARD_RATE_LIMIT ||
            invitationRepository.countByInvitedByUserIdAndCreatedAtAfter(inviterUserId, now.minus(RATE_WINDOW)) >= INVITER_RATE_LIMIT
        ) {
            throw InvitationRateLimitException()
        }

        val normalised = email.trim().lowercase()
        val emailHash = EmailHasher.hash(normalised)

        // Already a member? (Only resolvable if the address maps to an existing account.)
        val existingUser = userRepository.findByEmailHash(emailHash)
        if (existingUser != null &&
            boardMembershipRepository.findByUserIdAndBoardId(existingUser.id!!, boardId) != null
        ) {
            throw AlreadyMemberException()
        }

        val members = boardMembershipRepository.findAllByBoardId(boardId).size
        val pending = pendingInvitations(boardId).size
        if (members + pending >= MEMBER_CAP) throw MemberLimitException()

        // One pending invite per (board, email): a re-invite supersedes the prior one.
        invitationRepository.findAllByBoardIdAndEmailHashAndConsumedAtIsNullAndRevokedAtIsNull(boardId, emailHash)
            .forEach { it.revokedAt = now }

        val token = generateToken()
        val invitation = invitationRepository.save(BoardInvitationEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = boardId
            this.emailHash = emailHash
            this.token = token
            this.invitedByUserId = inviterUserId
            this.createdAt = now
            this.expiresAt = now.plus(TOKEN_TTL)
        })

        sendInviteEmail(normalised, inviterUserId, boardId, token)
        log.info("Board {} invitation sent by {} (emailHash={})", boardId, inviterUserId, emailHash)
        return invitation.toPending()
    }

    /** Pending invitations for a board (owner-only), address-free. */
    @Transactional(readOnly = true)
    fun listPending(userId: UUID, boardId: UUID): List<PendingInvitation> {
        requireOwner(userId, boardId)
        return pendingInvitations(boardId).map { it.toPending() }
    }

    /** Revokes a pending invitation (owner-only). Idempotent for an already-resolved invite. */
    @Transactional
    fun revoke(userId: UUID, boardId: UUID, invitationId: UUID) {
        requireOwner(userId, boardId)
        val invitation = invitationRepository.findById(invitationId).orElse(null) ?: return
        if (invitation.boardId != boardId) throw InvitationNotFoundException()
        if (invitation.consumedAt == null && invitation.revokedAt == null) {
            invitation.revokedAt = clock.instant()
            invitationRepository.save(invitation)
        }
    }

    /**
     * Side-effect-free preview for the accept screen. Invalid/expired/revoked/consumed tokens all
     * return a uniform 404 (the token is the only secret, so this leaks nothing).
     */
    @Transactional(readOnly = true)
    fun preview(token: String): InvitationPreview {
        val invitation = validPendingByToken(token) ?: throw InvitationNotFoundException()
        val boardId = invitation.boardId!!
        val board = boardRepository.findById(boardId).orElse(null) ?: throw InvitationNotFoundException()
        val inviterName = invitation.invitedByUserId
            ?.let { displayNameResolver.resolve(listOf(it))[it] }
            ?: "Someone"
        return InvitationPreview(
            boardName = boardCrypto.decrypt(boardId, board.name) ?: "",
            inviterName = inviterName,
            expiresAt = invitation.expiresAt!!,
        )
    }

    /**
     * Accepts [token] as [acceptorUserId]. Single-use: the token is consumed **before** the
     * membership is created (mirrors `completeLogin`), so a replay is rejected. No-ops if the
     * acceptor is already a member. Re-checks the member cap so a flurry of accepts can't overflow it.
     */
    @Transactional
    fun accept(token: String, acceptorUserId: UUID): AcceptedInvitation {
        val invitation = validPendingByToken(token) ?: throw InvitationNotFoundException()
        val boardId = invitation.boardId!!
        val board = boardRepository.findById(boardId).orElse(null) ?: throw InvitationNotFoundException()
        val now = clock.instant()

        invitation.consumedAt = now
        invitation.acceptedByUserId = acceptorUserId
        invitationRepository.save(invitation)

        val boardName = boardCrypto.decrypt(boardId, board.name) ?: ""
        val existing = boardMembershipRepository.findByUserIdAndBoardId(acceptorUserId, boardId)
        if (existing != null) {
            log.info("Invitation accepted by already-member {} for board {}", acceptorUserId, boardId)
            return AcceptedInvitation(boardId, boardName)
        }

        if (boardMembershipRepository.findAllByBoardId(boardId).size >= MEMBER_CAP) throw MemberLimitException()

        boardMembershipRepository.save(BoardMembershipEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = boardId
            this.userId = acceptorUserId
            this.role = BoardRole.MEMBER
            this.joinedAt = now
        })
        log.info("User {} joined board {} via invitation", acceptorUserId, boardId)
        return AcceptedInvitation(boardId, boardName)
    }

    /** Hourly housekeeping so expired invitations don't accumulate. */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    fun purgeExpired() {
        val removed = invitationRepository.deleteAllExpired(clock.instant())
        if (removed > 0) log.debug("Purged {} expired board invitation(s)", removed)
    }

    private fun sendInviteEmail(email: String, inviterUserId: UUID, boardId: UUID, token: String) {
        val locale = inviterLocale(inviterUserId)
        val board = boardRepository.findById(boardId).orElse(null)
        val boardName = board?.let { boardCrypto.decrypt(boardId, it.name) } ?: ""
        val inviterName = displayNameResolver.resolve(listOf(inviterUserId))[inviterUserId] ?: "Someone"
        val acceptUrl = "${appProperties.baseUrl}/invite?token=$token"

        val htmlBody = emailTemplateEngine.render(
            "emails/board-invitation.html",
            mapOf("accept_url" to acceptUrl, "board_name" to boardName, "inviter_name" to inviterName, "locale" to locale),
            locale,
        )
        val subject = messageSource.getMessage("email.boardInvite.subject", arrayOf(inviterName), locale)
        val textBody = messageSource.getMessage(
            "email.boardInvite.textBody", arrayOf(inviterName, boardName, acceptUrl), locale,
        )
        outboundChannel.send(
            EmailMessage(to = listOf(email), subject = subject, htmlBody = htmlBody, textBody = textBody),
        )
    }

    private fun inviterLocale(userId: UUID): Locale =
        userSettingsRepository.findById(userId).orElse(null)?.preferredLanguage
            ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            ?.takeIf { it.language.isNotEmpty() }
            ?: Locale.ENGLISH

    private fun pendingInvitations(boardId: UUID): List<BoardInvitationEntity> {
        val now = clock.instant()
        return invitationRepository.findAllByBoardIdAndConsumedAtIsNullAndRevokedAtIsNull(boardId)
            .filter { it.expiresAt != null && !now.isAfter(it.expiresAt) }
    }

    private fun validPendingByToken(token: String): BoardInvitationEntity? {
        val invitation = invitationRepository.findByToken(token) ?: return null
        val expiry = invitation.expiresAt ?: return null
        if (invitation.consumedAt != null || invitation.revokedAt != null || clock.instant().isAfter(expiry)) {
            return null
        }
        return invitation
    }

    private fun requireOwner(userId: UUID, boardId: UUID) {
        if (boardMembershipService.requireMember(userId, boardId) != BoardRole.OWNER) {
            throw BoardOwnerRequiredException()
        }
    }

    private fun generateToken(): String =
        UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "").take(8)

    private fun BoardInvitationEntity.toPending() = PendingInvitation(id!!, createdAt!!, expiresAt!!)

    companion object {
        private val TOKEN_TTL: Duration = Duration.ofDays(7)
        private val RATE_WINDOW: Duration = Duration.ofDays(1)
        private const val BOARD_RATE_LIMIT = 10L
        private const val INVITER_RATE_LIMIT = 10L
        const val MEMBER_CAP = 10
    }
}

/** Thrown when the invited address already belongs to a member of the board. Maps to HTTP 409. */
@ResponseStatus(HttpStatus.CONFLICT)
class AlreadyMemberException : RuntimeException("That person is already a member of this board")

/** Thrown when a board is at its member + pending-invite cap. Maps to HTTP 409. */
@ResponseStatus(HttpStatus.CONFLICT)
class MemberLimitException : RuntimeException("This board has reached its member limit")

/** Thrown when an inviter has no durable login method (e.g. a demo account). Maps to HTTP 403. */
@ResponseStatus(HttpStatus.FORBIDDEN)
class InviterNotEligibleException : RuntimeException("You need a registered login method to invite others")

/** Thrown when invite sends exceed the daily rate limit. Maps to HTTP 429. */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class InvitationRateLimitException : RuntimeException("Too many invitations sent; try again later")

/** Thrown for an invalid/expired/consumed/revoked invitation token. Maps to a uniform HTTP 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
class InvitationNotFoundException : RuntimeException("Invitation not found")

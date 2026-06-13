package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BoardEntity
import dev.itayp.tasker.jpa.BoardInvitationEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.BoardInvitationRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BoardInvitationServiceTest {

    @Mock private lateinit var invitationRepository: BoardInvitationRepository
    @Mock private lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var authIdentityRepository: AuthIdentityRepository
    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var userSettingsRepository: UserSettingsRepository
    @Mock private lateinit var boardRepository: BoardRepository
    @Mock private lateinit var displayNameResolver: MemberDisplayNameResolver
    @Mock private lateinit var outboundChannel: OutboundChannel
    @Mock private lateinit var emailTemplateEngine: EmailTemplateEngine

    private val messageSource = org.springframework.context.support.StaticMessageSource().apply {
        addMessage("email.boardInvite.subject", java.util.Locale.ENGLISH, "{0} invited you")
        addMessage("email.boardInvite.textBody", java.util.Locale.ENGLISH, "{0} {1} {2}")
    }
    private val now = Instant.parse("2026-06-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val boardCrypto = noopBoardCryptoService()

    private val service by lazy {
        BoardInvitationService(
            invitationRepository, boardMembershipRepository, boardMembershipService, authIdentityRepository,
            userRepository, userSettingsRepository, boardRepository, boardCrypto, displayNameResolver,
            outboundChannel, emailTemplateEngine, messageSource, AppProperties(baseUrl = "https://test.local"), clock,
        )
    }

    private val boardId = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val email = "bob@example.com"

    private fun primeOwnerInviter() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        whenever(authIdentityRepository.existsByUserId(owner)).thenReturn(true)
        whenever(invitationRepository.countByBoardIdAndCreatedAtAfter(eq(boardId), any())).thenReturn(0)
        whenever(invitationRepository.countByInvitedByUserIdAndCreatedAtAfter(eq(owner), any())).thenReturn(0)
        whenever(userRepository.findByEmailHash(any())).thenReturn(null)
        whenever(boardMembershipRepository.findAllByBoardId(boardId))
            .thenReturn(listOf(BoardMembershipEntity().apply { userId = owner; role = BoardRole.OWNER }))
        whenever(invitationRepository.findAllByBoardIdAndConsumedAtIsNullAndRevokedAtIsNull(boardId)).thenReturn(emptyList())
        whenever(invitationRepository.findAllByBoardIdAndEmailHashAndConsumedAtIsNullAndRevokedAtIsNull(eq(boardId), any()))
            .thenReturn(emptyList())
        whenever(invitationRepository.save(any<BoardInvitationEntity>())).thenAnswer { it.arguments[0] }
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(BoardEntity().apply { id = boardId; createdAt = now }))
        whenever(displayNameResolver.resolve(any())).thenReturn(mapOf(owner to "Alice"))
        whenever(userSettingsRepository.findById(owner)).thenReturn(Optional.empty())
        whenever(emailTemplateEngine.render(any(), any(), any())).thenReturn("<html></html>")
    }

    // ── invite ────────────────────────────────────────────────────────────────

    @Test
    fun `invite creates a pending token and sends the email`() {
        primeOwnerInviter()

        val pending = service.invite(owner, boardId, email)

        val saved = argumentCaptor<BoardInvitationEntity>()
        verify(invitationRepository).save(saved.capture())
        assertEquals(boardId, saved.firstValue.boardId)
        assertEquals(EmailHasher.hash(email), saved.firstValue.emailHash)
        assertEquals(owner, saved.firstValue.invitedByUserId)
        assertEquals(now.plus(java.time.Duration.ofDays(7)), saved.firstValue.expiresAt)
        assertEquals(saved.firstValue.id, pending.id)
        verify(outboundChannel).send(any())
    }

    @Test
    fun `invite by a non-owner is refused`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.MEMBER)

        assertThrows<BoardOwnerRequiredException> { service.invite(owner, boardId, email) }
        verify(invitationRepository, never()).save(any())
    }

    @Test
    fun `invite by an inviter with no login method is refused`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        whenever(authIdentityRepository.existsByUserId(owner)).thenReturn(false)

        assertThrows<InviterNotEligibleException> { service.invite(owner, boardId, email) }
    }

    @Test
    fun `invite to an existing member is refused`() {
        primeOwnerInviter()
        val existing = UserEntity().apply { id = UUID.randomUUID() }
        whenever(userRepository.findByEmailHash(any())).thenReturn(existing)
        whenever(boardMembershipRepository.findByUserIdAndBoardId(existing.id!!, boardId))
            .thenReturn(BoardMembershipEntity().apply { userId = existing.id; role = BoardRole.MEMBER })

        assertThrows<AlreadyMemberException> { service.invite(owner, boardId, email) }
    }

    @Test
    fun `invite is refused at the member + pending cap`() {
        primeOwnerInviter()
        val members = (1..BoardInvitationService.MEMBER_CAP).map {
            BoardMembershipEntity().apply { userId = UUID.randomUUID(); role = BoardRole.MEMBER }
        }
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(members)

        assertThrows<MemberLimitException> { service.invite(owner, boardId, email) }
    }

    @Test
    fun `invite is rate-limited`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        whenever(authIdentityRepository.existsByUserId(owner)).thenReturn(true)
        whenever(invitationRepository.countByBoardIdAndCreatedAtAfter(eq(boardId), any())).thenReturn(10)

        assertThrows<InvitationRateLimitException> { service.invite(owner, boardId, email) }
    }

    @Test
    fun `re-invite revokes the prior pending invitation`() {
        primeOwnerInviter()
        val prior = BoardInvitationEntity().apply {
            id = UUID.randomUUID(); boardId = this@BoardInvitationServiceTest.boardId
            emailHash = EmailHasher.hash(email); createdAt = now.minusSeconds(100); expiresAt = now.plusSeconds(1000)
        }
        whenever(invitationRepository.findAllByBoardIdAndEmailHashAndConsumedAtIsNullAndRevokedAtIsNull(eq(boardId), any()))
            .thenReturn(listOf(prior))

        service.invite(owner, boardId, email)

        assertEquals(now, prior.revokedAt)
    }

    // ── accept ────────────────────────────────────────────────────────────────

    @Test
    fun `accept consumes the token and creates a MEMBER membership`() {
        val acceptor = UUID.randomUUID()
        val invitation = pendingInvitation()
        whenever(invitationRepository.findByToken("tok")).thenReturn(invitation)
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(BoardEntity().apply { id = boardId; createdAt = now }))
        whenever(boardMembershipRepository.findByUserIdAndBoardId(acceptor, boardId)).thenReturn(null)
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(boardMembershipRepository.save(any<BoardMembershipEntity>())).thenAnswer { it.arguments[0] }

        val result = service.accept("tok", acceptor)

        assertEquals(boardId, result.boardId)
        assertEquals(now, invitation.consumedAt)
        assertEquals(acceptor, invitation.acceptedByUserId)
        val membership = argumentCaptor<BoardMembershipEntity>()
        verify(boardMembershipRepository).save(membership.capture())
        assertEquals(BoardRole.MEMBER, membership.firstValue.role)
        assertEquals(acceptor, membership.firstValue.userId)
    }

    @Test
    fun `accept is a no-op for an existing member but still consumes the token`() {
        val acceptor = UUID.randomUUID()
        val invitation = pendingInvitation()
        whenever(invitationRepository.findByToken("tok")).thenReturn(invitation)
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(BoardEntity().apply { id = boardId; createdAt = now }))
        whenever(boardMembershipRepository.findByUserIdAndBoardId(acceptor, boardId))
            .thenReturn(BoardMembershipEntity().apply { userId = acceptor; role = BoardRole.MEMBER })

        service.accept("tok", acceptor)

        assertEquals(now, invitation.consumedAt)
        verify(boardMembershipRepository, never()).save(any())
    }

    @Test
    fun `accept rejects a consumed token`() {
        val invitation = pendingInvitation().apply { consumedAt = now.minusSeconds(10) }
        whenever(invitationRepository.findByToken("tok")).thenReturn(invitation)

        assertThrows<InvitationNotFoundException> { service.accept("tok", UUID.randomUUID()) }
    }

    @Test
    fun `accept rejects an expired token`() {
        val invitation = pendingInvitation().apply { expiresAt = now.minusSeconds(1) }
        whenever(invitationRepository.findByToken("tok")).thenReturn(invitation)

        assertThrows<InvitationNotFoundException> { service.accept("tok", UUID.randomUUID()) }
    }

    @Test
    fun `accept rejects an unknown token`() {
        whenever(invitationRepository.findByToken("nope")).thenReturn(null)

        assertThrows<InvitationNotFoundException> { service.accept("nope", UUID.randomUUID()) }
    }

    // ── preview / revoke ────────────────────────────────────────────────────────

    @Test
    fun `preview returns board and inviter for a valid token`() {
        val invitation = pendingInvitation()
        whenever(invitationRepository.findByToken("tok")).thenReturn(invitation)
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(BoardEntity().apply { id = boardId; name = "Home".toByteArray(); createdAt = now }))
        whenever(displayNameResolver.resolve(listOf(owner))).thenReturn(mapOf(owner to "Alice"))

        val preview = service.preview("tok")

        assertEquals("Home", preview.boardName)
        assertEquals("Alice", preview.inviterName)
    }

    @Test
    fun `revoke marks a pending invitation revoked`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        val invitation = pendingInvitation()
        whenever(invitationRepository.findById(invitation.id!!)).thenReturn(Optional.of(invitation))

        service.revoke(owner, boardId, invitation.id!!)

        assertEquals(now, invitation.revokedAt)
    }

    private fun pendingInvitation() = BoardInvitationEntity().apply {
        id = UUID.randomUUID()
        boardId = this@BoardInvitationServiceTest.boardId
        emailHash = EmailHasher.hash(email)
        token = "tok"
        invitedByUserId = owner
        createdAt = now.minusSeconds(100)
        expiresAt = now.plus(java.time.Duration.ofDays(7))
    }
}

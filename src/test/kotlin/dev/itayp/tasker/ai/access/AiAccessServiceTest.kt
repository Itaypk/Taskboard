package dev.itayp.tasker.ai.access

import dev.itayp.tasker.ai.usage.AiUsageEventRepository
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class AiAccessServiceTest {

    @Mock lateinit var userSettingsRepository: UserSettingsRepository
    @Mock lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock lateinit var usageRepository: AiUsageEventRepository

    private val clock = Clock.fixed(Instant.parse("2026-06-19T10:00:00Z"), ZoneOffset.UTC)

    private val service by lazy {
        AiAccessService(
            userSettingsRepository,
            boardMembershipRepository,
            usageRepository,
            clock,
        )
    }

    private val userId = UUID.randomUUID()
    private val coMemberId = UUID.randomUUID()
    private val boardId = UUID.randomUUID()

    private fun settings(uid: UUID, enabled: Boolean = true, tier: String = "standard") =
        UserSettingsEntity().apply {
            userId = uid
            aiEnabled = enabled
            aiTier = tier
        }

    private fun membership(uid: UUID, bid: UUID) = BoardMembershipEntity().apply {
        id = UUID.randomUUID()
        userId = uid
        boardId = bid
    }

    @Test
    fun `requireAiEnabledForUser throws when the user has opted out`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = false)))

        assertFailsWith<AiDisabledException> { service.requireAiEnabledForUser(userId) }
    }

    @Test
    fun `requireAiEnabledForUser passes when the user is opted in, regardless of co-member opt-outs`() {
        // The per-user gate must not block on what other people did — they only block AI on the
        // shared board (requireAiEnabledForBoard). The opted-in user keeps AI on their own boards.
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = true)))

        service.requireAiEnabledForUser(userId)
    }

    @Test
    fun `requireAiEnabledForBoard throws when any member of the board opted out`() {
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId), membership(coMemberId, boardId))
        )
        whenever(userSettingsRepository.findAllById(eq(listOf(userId, coMemberId)))).thenReturn(
            listOf(settings(userId, enabled = true), settings(coMemberId, enabled = false))
        )

        assertFailsWith<AiDisabledException> { service.requireAiEnabledForBoard(boardId) }
    }

    @Test
    fun `requireAiEnabledForBoard passes when every member is opted in`() {
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId))
        )
        whenever(userSettingsRepository.findAllById(eq(listOf(userId)))).thenReturn(
            listOf(settings(userId, enabled = true))
        )

        service.requireAiEnabledForBoard(boardId)
    }

    @Test
    fun `requireWithinTierLimit throws when STANDARD tier budget is consumed`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "standard")))
        whenever(usageRepository.sumTotalTokensByUserIdSince(eq(userId), any<Instant>()))
            .thenReturn(AiTier.STANDARD.monthlyTokenLimit!!)

        assertFailsWith<AiUsageLimitExceededException> { service.requireWithinTierLimit(userId) }
    }

    @Test
    fun `requireWithinTierLimit is a no-op for UNLIMITED tier`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "unlimited")))

        service.requireWithinTierLimit(userId)
    }

    @Test
    fun `isAiEnabledForBoard is false when any member opts out`() {
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId), membership(coMemberId, boardId))
        )
        whenever(userSettingsRepository.findAllById(eq(listOf(userId, coMemberId)))).thenReturn(
            listOf(settings(userId, enabled = true), settings(coMemberId, enabled = false))
        )

        assertFalse(service.isAiEnabledForBoard(boardId))
    }

    @Test
    fun `tierFor falls back to NONE for unknown names`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "platinum")))
        assertEquals(AiTier.NONE, service.tierFor(userId))
    }

    @Test
    fun `requireAiTierGranted throws for NONE and an unrecognized tier`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "none")))
        assertFailsWith<AiTierNotGrantedException> { service.requireAiTierGranted(userId) }

        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "platinum")))
        assertFailsWith<AiTierNotGrantedException> { service.requireAiTierGranted(userId) }
    }

    @Test
    fun `requireAiTierGranted passes for STANDARD and UNLIMITED`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "standard")))
        service.requireAiTierGranted(userId)

        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "unlimited")))
        service.requireAiTierGranted(userId)
    }

    @Test
    fun `isAiAvailableForUser is false when the tier isn't granted, even with AI enabled`() {
        // Short-circuits on the tier check before ever consulting board membership.
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = true, tier = "none")))

        assertFalse(service.isAiAvailableForUser(userId))
    }
}

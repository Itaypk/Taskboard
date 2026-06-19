package dev.itayp.tasker.ai.access

import dev.itayp.tasker.ai.usage.AiUsageEventRepository
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.BoardMembershipService
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
    @Mock lateinit var boardMembershipService: BoardMembershipService
    @Mock lateinit var usageRepository: AiUsageEventRepository

    private val clock = Clock.fixed(Instant.parse("2026-06-19T10:00:00Z"), ZoneOffset.UTC)

    private val service by lazy {
        AiAccessService(
            userSettingsRepository,
            boardMembershipRepository,
            boardMembershipService,
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
    fun `requireAiAllowedForUser throws when the user has opted out`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = false)))

        assertFailsWith<AiDisabledException> { service.requireAiAllowedForUser(userId) }
    }

    @Test
    fun `requireAiAllowedForUser throws when a co-member of a shared board has opted out`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = true)))
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId), membership(coMemberId, boardId))
        )
        whenever(userSettingsRepository.findAllById(eq(listOf(coMemberId))))
            .thenReturn(listOf(settings(coMemberId, enabled = false)))

        assertFailsWith<AiDisabledException> { service.requireAiAllowedForUser(userId) }
    }

    @Test
    fun `requireAiAllowedForUser passes for solo board with AI on`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, enabled = true)))
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId))
        )

        service.requireAiAllowedForUser(userId)
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
    fun `isAiEnabledForBoard is true when every member has AI on`() {
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(
            listOf(membership(userId, boardId))
        )
        whenever(userSettingsRepository.findAllById(eq(listOf(userId)))).thenReturn(
            listOf(settings(userId, enabled = true))
        )

        assertTrue(service.isAiEnabledForBoard(boardId))
    }

    @Test
    fun `tierFor falls back to STANDARD for unknown names`() {
        whenever(userSettingsRepository.findById(userId)).thenReturn(Optional.of(settings(userId, tier = "platinum")))
        assertEquals(AiTier.STANDARD, service.tierFor(userId))
    }
}

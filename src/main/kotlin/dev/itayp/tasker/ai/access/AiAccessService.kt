package dev.itayp.tasker.ai.access

import dev.itayp.tasker.ai.usage.AiUsageEventRepository
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.repository.BoardMembershipRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Central authority for "is this user/board allowed to use AI right now?". Two independent
 * gates flow through here:
 *   - the per-user opt-out (`ai_enabled`) — a hard binary toggle the user controls in settings;
 *   - the per-tier rolling-window token budget (`ai_tier`) — currently STANDARD for everyone,
 *     enforced by the [dev.itayp.tasker.ai.client.AiCallGate].
 *
 * Shared-board rule: AI work scoped to a board (planning, suggestions over board data) requires
 * every member of that board to have AI enabled. If any member opts out, the whole board's AI
 * features are unavailable to everyone — opting out covers data-on-shared-boards too.
 */
@Service
class AiAccessService(
    private val userSettingsRepository: UserSettingsRepository,
    private val boardMembershipRepository: BoardMembershipRepository,
    private val boardMembershipService: BoardMembershipService,
    private val usageRepository: AiUsageEventRepository,
    private val clock: Clock,
) {

    fun isAiEnabledForUser(userId: UUID): Boolean =
        userSettingsRepository.findById(userId).map { it.aiEnabled }.orElse(true)

    fun tierFor(userId: UUID): AiTier =
        AiTier.fromName(userSettingsRepository.findById(userId).map { it.aiTier }.orElse(null))

    /**
     * True iff every member of [boardId] has AI enabled. Used by callers that act over board
     * data (the planner, board-scoped suggestions). For solo boards this collapses to the
     * single member's toggle.
     */
    fun isAiEnabledForBoard(boardId: UUID): Boolean {
        val memberIds = boardMembershipRepository.findAllByBoardId(boardId)
            .mapNotNull { it.userId }
        if (memberIds.isEmpty()) return true
        return userSettingsRepository.findAllById(memberIds).all { it.aiEnabled }
    }

    /**
     * Throws [AiDisabledException] if the calling user has opted out, or if any board they belong
     * to has another member who opted out (so a co-member can block AI access to shared data).
     * This is the broad check the call gate runs before every outbound AI request.
     */
    fun requireAiAllowedForUser(userId: UUID) {
        val settings: UserSettingsEntity? = userSettingsRepository.findById(userId).orElse(null)
        if (settings != null && !settings.aiEnabled) {
            throw AiDisabledException("User $userId has AI disabled")
        }
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return
        val otherMembers = boardIds
            .flatMap { boardMembershipRepository.findAllByBoardId(it) }
            .mapNotNull { it.userId }
            .filter { it != userId }
            .distinct()
        if (otherMembers.isEmpty()) return
        val anyOptedOut = userSettingsRepository.findAllById(otherMembers).any { !it.aiEnabled }
        if (anyOptedOut) {
            throw AiDisabledException("User $userId shares a board with a member who has AI disabled")
        }
    }

    /**
     * Throws [AiUsageLimitExceededException] if the user has consumed at least the tier's monthly
     * token budget over the trailing 30 days. UNLIMITED tiers always pass.
     */
    fun requireWithinTierLimit(userId: UUID) {
        val limit = tierFor(userId).monthlyTokenLimit ?: return
        val since = clock.instant().minus(Duration.ofDays(30))
        val used = usageRepository.sumTotalTokensByUserIdSince(userId, since)
        if (used >= limit) {
            throw AiUsageLimitExceededException(userId, used, limit)
        }
    }
}

@ResponseStatus(HttpStatus.FORBIDDEN)
class AiDisabledException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class AiUsageLimitExceededException(
    val userId: UUID,
    val usedTokens: Long,
    val limitTokens: Long,
) : RuntimeException("User $userId exceeded AI tier limit: $usedTokens/$limitTokens tokens in the last 30 days")

package dev.itayp.tasker.ai.access

import dev.itayp.tasker.ai.usage.AiUsageEventRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.UserSettingsRepository
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
 *     enforced by the [dev.itayp.nescioquid.openrouter.AiCallGate].
 *
 * The shared-board veto is **per board**, not per user: a co-member's opt-out blocks AI on
 * the shared board only. The opted-in user keeps using AI on their own / other boards. Callers
 * operating against a specific board (planner, board-scoped tools) call
 * [requireAiEnabledForBoard]; the per-call gate only checks the user-level toggle and tier.
 */
@Service
class AiAccessService(
    private val userSettingsRepository: UserSettingsRepository,
    private val boardMembershipRepository: BoardMembershipRepository,
    private val usageRepository: AiUsageEventRepository,
    private val clock: Clock,
) {

    fun isAiEnabledForUser(userId: UUID): Boolean =
        userSettingsRepository.findById(userId).map { it.aiEnabled }.orElse(true)

    fun tierFor(userId: UUID): AiTier =
        AiTier.fromName(userSettingsRepository.findById(userId).map { it.aiTier }.orElse(null))

    /**
     * True iff every member of [boardId] has AI enabled. For solo boards this collapses to the
     * single member's toggle.
     */
    fun isAiEnabledForBoard(boardId: UUID): Boolean {
        val memberIds = boardMembershipRepository.findAllByBoardId(boardId)
            .mapNotNull { it.userId }
        if (memberIds.isEmpty()) return true
        return userSettingsRepository.findAllById(memberIds).all { it.aiEnabled }
    }

    /**
     * Subset of [userId]'s boards whose every member has AI enabled. Drives the "only suggest
     * tasks from non-restricted boards" rule in the planner and the LLM's `create_task` choices.
     */
    fun aiAllowedBoardIds(userId: UUID): Set<UUID> {
        val memberBoards = boardMembershipRepository.findAllByUserId(userId)
            .mapNotNull { it.boardId }
            .toSet()
        if (memberBoards.isEmpty()) return emptySet()
        return memberBoards.filterTo(mutableSetOf()) { isAiEnabledForBoard(it) }
    }

    /**
     * Composite "can this user use AI right now?" — true iff they themselves are opted in AND have
     * at least one board where AI is allowed. Used by the web planning entry and the Telegram
     * dispatcher to disable AI affordances when there's nothing to plan against.
     */
    fun isAiAvailableForUser(userId: UUID): Boolean =
        isAiEnabledForUser(userId) && aiAllowedBoardIds(userId).isNotEmpty()

    /** Throws [AiDisabledException] when the caller has flipped AI off in their settings. */
    fun requireAiEnabledForUser(userId: UUID) {
        if (!isAiEnabledForUser(userId)) {
            throw AiDisabledException("User $userId has AI disabled")
        }
    }

    /**
     * Throws [AiDisabledException] when any member of [boardId] has flipped AI off. Use this
     * at every AI entry point that reads or writes board data — the per-user gate is *not*
     * a substitute, since it intentionally ignores co-member opt-outs to let the caller keep
     * using AI on boards where everyone is opted in.
     */
    fun requireAiEnabledForBoard(boardId: UUID) {
        if (!isAiEnabledForBoard(boardId)) {
            throw AiDisabledException("Board $boardId has a member with AI disabled")
        }
    }

    /**
     * Throws [AiUsageLimitExceededException] if the user has consumed at least the tier's monthly
     * token budget over the trailing 30 days. UNLIMITED tiers always pass.
     */
    fun requireWithinTierLimit(userId: UUID) {
        val limit = tierFor(userId).monthlyTokenLimit ?: return
        val since = clock.instant().minus(Duration.ofDays(WINDOW_DAYS))
        val used = usageRepository.sumTotalTokensByUserIdSince(userId, since)
        if (used >= limit) {
            throw AiUsageLimitExceededException(userId, used, limit)
        }
    }

    /**
     * Snapshot of the user's tier and how much of its rolling-window budget they've spent — drives
     * the usage meter in settings. [AiUsageSummary.limitTokens] is null for UNLIMITED tiers.
     */
    fun usageSummaryFor(userId: UUID): AiUsageSummary {
        val tier = tierFor(userId)
        val since = clock.instant().minus(Duration.ofDays(WINDOW_DAYS))
        val used = usageRepository.sumTotalTokensByUserIdSince(userId, since)
        return AiUsageSummary(
            tier = tier.tierName,
            usedTokens = used,
            limitTokens = tier.monthlyTokenLimit,
            windowDays = WINDOW_DAYS.toInt(),
        )
    }

    private companion object {
        const val WINDOW_DAYS = 30L
    }
}

data class AiUsageSummary(
    val tier: String,
    val usedTokens: Long,
    val limitTokens: Long?,
    val windowDays: Int,
)

@ResponseStatus(HttpStatus.FORBIDDEN)
class AiDisabledException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class AiUsageLimitExceededException(
    val userId: UUID,
    val usedTokens: Long,
    val limitTokens: Long,
) : RuntimeException("User $userId exceeded AI tier limit: $usedTokens/$limitTokens tokens in the last 30 days")

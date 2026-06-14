package dev.itayp.tasker.service

import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Reclaims never-claimed, inactive accounts (the old demo-cleanup, generalised). `claimed = false`
 * is the hard guard — a claimed account is never touched regardless of activity. Among unclaimed
 * accounts, the inactivity window depends on engagement: a tire-kicker that never did real work is
 * swept after [SHORT_TTL]; one that engaged (created a real task) but never added a login method
 * gets [LONG_TTL] of runway to come back and claim. See docs/DEMO-ACCOUNT-UNIFICATION.md.
 */
@Service
class UnclaimedAccountCleanupService(
    private val userRepository: UserRepository,
    private val accountService: AccountService,
    private val clock: Clock,
) {

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    fun cleanupUnclaimedAccounts() {
        val now = clock.instant()
        val sweepable = userRepository.findSweepableAccounts(
            shortCutoff = now.minus(SHORT_TTL),
            longCutoff = now.minus(LONG_TTL),
        )
        if (sweepable.isEmpty()) return

        logger.info("Cleaning up ${sweepable.size} unclaimed, inactive account(s)")
        for (user in sweepable) {
            accountService.deleteUserData(user.id!!)
        }
        // Batch-delete user rows after all associated data is gone.
        userRepository.deleteAll(sweepable)
        logger.info("Unclaimed-account cleanup complete")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UnclaimedAccountCleanupService::class.java)

        /** Created but never engaged (no real task / planning). */
        private val SHORT_TTL: Duration = Duration.ofDays(2)

        /** Engaged (did real work) but never claimed by adding a login method. */
        private val LONG_TTL: Duration = Duration.ofDays(14)
    }
}

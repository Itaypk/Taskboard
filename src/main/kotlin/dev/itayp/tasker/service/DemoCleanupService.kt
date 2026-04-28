package dev.itayp.tasker.service

import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class DemoCleanupService(
    private val userRepository: UserRepository,
    private val accountService: AccountService,
) {

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    fun cleanupExpiredDemoUsers() {
        val expired = userRepository.findExpiredDemoUsers(Instant.now())
        if (expired.isEmpty()) return

        logger.info("Cleaning up ${expired.size} expired demo user(s)")

        for (user in expired) {
            accountService.deleteUserData(user.id!!)
        }
        // Batch-delete user rows after all associated data is gone.
        userRepository.deleteAll(expired)

        logger.info("Demo cleanup complete")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DemoCleanupService::class.java)
    }
}

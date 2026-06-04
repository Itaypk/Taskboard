package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class TaskAutoArchiveService(
    private val userSettingsRepository: UserSettingsRepository,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val taskChangeService: BacklogTaskChangeService,
    private val userCrypto: UserCryptoService,
    private val clock: Clock,
) {

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    @Transactional
    fun archiveStaleDoneTasks() {
        val users = userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()
        if (users.isEmpty()) return

        val now = clock.instant()
        var totalArchived = 0

        for (settings in users) {
            val userId = settings.userId ?: continue
            val cutoff = now.minus(settings.autoArchiveDays!!.toLong(), ChronoUnit.DAYS)
            val stale = backlogTaskRepository
                .findAllByUserIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(userId, TaskStatus.DONE, cutoff)

            for (entity in stale) {
                entity.status = TaskStatus.ARCHIVED
                entity.updatedAt = now
                backlogTaskRepository.save(entity)
                val plaintextTitle = userCrypto.decrypt(userId, entity.title) ?: ""
                taskChangeService.recordStatusChange(
                    userId, entity.id!!, plaintextTitle, TaskStatus.DONE, TaskStatus.ARCHIVED
                )
            }
            if (stale.isNotEmpty()) {
                taskChangeService.bumpWatermark(userId)
            }
            totalArchived += stale.size
        }

        if (totalArchived > 0) {
            logger.info("Auto-archived $totalArchived done task(s) across ${users.size} user(s)")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TaskAutoArchiveService::class.java)
    }
}

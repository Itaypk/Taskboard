package dev.itayp.tasker.service

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
    private val clock: Clock,
) {

    @Scheduled(cron = "0 30 2 * * *")
    @Transactional
    fun archiveStaleDoneTasks() {
        val users = userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()
        if (users.isEmpty()) return

        val now = clock.instant()
        var totalArchived = 0

        for (settings in users) {
            val cutoff = now.minus(settings.autoArchiveDays!!.toLong(), ChronoUnit.DAYS)
            val stale = backlogTaskRepository
                .findAllByUserIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(settings.userId!!, TaskStatus.DONE, cutoff)

            for (entity in stale) {
                entity.status = TaskStatus.ARCHIVED
                entity.updatedAt = now
                backlogTaskRepository.save(entity)
                taskChangeService.recordStatusChange(
                    settings.userId!!, entity.id!!, entity.title!!, TaskStatus.DONE, TaskStatus.ARCHIVED
                )
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

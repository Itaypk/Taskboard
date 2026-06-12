package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
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
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
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
            // auto_archive_days is a per-user setting applied to every board the user belongs to.
            // (When shared boards arrive, revisit whether archiving should be board-level — see
            // docs/BOARD-SHARING.md.)
            val boardIds = runCatching { boardMembershipService.listBoardIds(userId) }.getOrNull() ?: continue
            val cutoff = now.minus(settings.autoArchiveDays!!.toLong(), ChronoUnit.DAYS)
            var userArchived = 0

            for (boardId in boardIds) {
                val stale = backlogTaskRepository
                    .findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(boardId, TaskStatus.DONE, cutoff)
                for (entity in stale) {
                    entity.status = TaskStatus.ARCHIVED
                    entity.updatedAt = now
                    backlogTaskRepository.save(entity)
                    val plaintextTitle = boardCrypto.decrypt(boardId, entity.title) ?: ""
                    taskChangeService.recordStatusChange(
                        boardId, userId, entity.id!!, plaintextTitle, TaskStatus.DONE, TaskStatus.ARCHIVED
                    )
                }
                // The watermark is board-keyed, so bump each board we actually swept.
                if (stale.isNotEmpty()) {
                    taskChangeService.bumpWatermark(boardId)
                }
                userArchived += stale.size
            }

            totalArchived += userArchived
        }

        if (totalArchived > 0) {
            logger.info("Auto-archived $totalArchived done task(s) across ${users.size} user(s)")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TaskAutoArchiveService::class.java)
    }
}

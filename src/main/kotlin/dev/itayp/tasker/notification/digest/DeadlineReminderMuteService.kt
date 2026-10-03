package dev.itayp.tasker.notification.digest

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Per-user mutes for the daily digest's "due tasks" section (see [DeadlineReminderMuteEntity] for
 * why they are per user and tied to a deadline).
 */
@Service
class DeadlineReminderMuteService(
    private val repository: DeadlineReminderMuteRepository,
) {
    private val log = LoggerFactory.getLogger(DeadlineReminderMuteService::class.java)

    /**
     * Mutes each of [tasks] at the deadline listed for it, until [until] (exclusive). Re-muting a task
     * replaces its earlier mute, so the latest tap always wins.
     */
    @Transactional
    fun mute(userId: UUID, tasks: List<DigestDueTask>, until: LocalDate) {
        if (tasks.isEmpty()) return
        val existing = repository
            .findAllByUserIdAndBacklogTaskIdIn(userId, tasks.mapNotNull { it.backlogTaskId })
            .associateBy { it.backlogTaskId }
        val rows = tasks.map { task ->
            (existing[task.backlogTaskId] ?: DeadlineReminderMuteEntity().apply {
                this.userId = userId
                backlogTaskId = task.backlogTaskId
            }).apply {
                deadline = task.deadline
                mutedUntil = until
            }
        }
        repository.saveAll(rows)
        log.info("Muted {} due task(s) for user {} until {}", rows.size, userId, until)
    }

    /** The user's mutes keyed by task id. Whether a mute still applies is [DeadlineReminderMuteEntity.silences]. */
    @Transactional(readOnly = true)
    fun findMutes(userId: UUID): Map<UUID, DeadlineReminderMuteEntity> =
        repository.findAllByUserId(userId).associateBy { it.backlogTaskId!! }

    /** Turns due-task reminders back on for every task. Returns how many mutes were removed. */
    @Transactional
    fun clearAll(userId: UUID): Long {
        val removed = repository.deleteByUserId(userId)
        log.info("Cleared {} due-task mute(s) for user {}", removed, userId)
        return removed
    }
}

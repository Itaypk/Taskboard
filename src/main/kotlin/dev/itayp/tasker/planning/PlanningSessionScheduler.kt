package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * Single-instance weekly-planning cron scheduler. Each user with a [UserSettingsEntity.planningCron]
 * gets one [ScheduledFuture] registered on the shared [TaskScheduler]; firing it kicks off
 * [WeeklyPlanningOrchestrator.start] over the user's Telegram channel.
 *
 * Rescheduling is driven by [UserPlanningScheduleChangedEvent] published from
 * `UserSettingsService.update`. The scheduler is fail-soft: invalid cron expressions or
 * missing Telegram wiring log a warning and skip rather than throw.
 */
@Component
class PlanningSessionScheduler(
    private val taskScheduler: TaskScheduler,
    private val settingsRepository: UserSettingsRepository,
    private val userRepository: UserRepository,
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val telegramClient: TelegramClient?,
    private val sessionRegistry: TelegramSessionRegistry?,
) {
    private val log = LoggerFactory.getLogger(PlanningSessionScheduler::class.java)
    private val futures = ConcurrentHashMap<UUID, ScheduledFuture<*>>()

    @EventListener(ApplicationReadyEvent::class)
    fun scheduleAllOnStartup() {
        val all = settingsRepository.findAllByPlanningCronIsNotNull()
        log.info("Scheduling weekly planning for {} user(s)", all.size)
        for (settings in all) {
            val userId = settings.userId ?: continue
            scheduleFor(userId)
        }
    }

    @EventListener
    fun onScheduleChanged(event: UserPlanningScheduleChangedEvent) {
        scheduleFor(event.userId)
    }

    @Synchronized
    fun scheduleFor(userId: UUID) {
        futures.remove(userId)?.cancel(false)
        val settings = settingsRepository.findById(userId).orElse(null) ?: return
        val cron = settings.planningCron ?: return
        val zoneId = runCatching { ZoneId.of(settings.timeZone) }.getOrElse {
            log.warn("User {} has invalid time zone '{}'; skipping schedule", userId, settings.timeZone)
            return
        }
        val trigger = runCatching { CronTrigger(cron, zoneId) }.getOrElse {
            log.warn("User {} has invalid cron '{}'; skipping schedule", userId, cron)
            return
        }
        val future = taskScheduler.schedule({ runPlanningSession(userId) }, trigger)
        if (future != null) {
            futures[userId] = future
            log.info("Scheduled weekly planning for user {} with cron '{}' (zone {})", userId, cron, zoneId)
        }
    }

    internal fun runPlanningSession(userId: UUID) {
        try {
            val client = telegramClient
            if (client == null) {
                log.warn("Telegram is not configured; cannot start scheduled session for user {}", userId)
                return
            }
            val chatId = userRepository.findById(userId).orElse(null)?.telegramId
            if (chatId == null) {
                log.warn("User {} has no Telegram id; cannot start scheduled session", userId)
                return
            }
            val channel: ConversationChannel = TelegramConversationChannel(chatId, client)
            val sessionId = orchestrator.start(userId, channel)
            sessionRegistry?.put(chatId, sessionId)
        } catch (e: Exception) {
            log.error("Failed to run scheduled planning session for user {}", userId, e)
        }
    }
}

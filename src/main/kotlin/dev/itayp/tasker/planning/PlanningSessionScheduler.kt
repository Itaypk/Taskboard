package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.repository.UserSettingsRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * Single-instance weekly-planning cron scheduler. Each user with a [UserSettingsEntity.planningCron]
 * *and* a deliverable conversation channel gets one [ScheduledFuture] registered on the shared
 * [TaskScheduler]; firing it kicks off [WeeklyPlanningOrchestrator.start] over that channel.
 *
 * The push channel itself is resolved by [ScheduledConversationChannelResolver], which owns the
 * "scheduled planning needs Telegram" assumption. Users with no deliverable channel are skipped
 * rather than scheduled into a job that could only no-op.
 *
 * Rescheduling is driven by [UserPlanningScheduleChangedEvent] published from
 * `UserSettingsService.update` (and should be re-published when a push channel is linked). The
 * scheduler is fail-soft: invalid cron expressions or a missing channel log and skip rather than throw.
 */
@Component
class PlanningSessionScheduler(
    private val taskScheduler: TaskScheduler,
    private val settingsRepository: UserSettingsRepository,
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val planningSessionService: PlanningSessionService,
    private val userSettingsService: UserSettingsService,
    private val channelResolver: ScheduledConversationChannelResolver,
    private val clock: Clock,
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
        // Don't register a cron that could only ever no-op: scheduled planning needs a channel
        // that can push to the user unprompted. Channel-less users (e.g. email-only) are skipped;
        // linking such a channel later should re-publish UserPlanningScheduleChangedEvent.
        if (!channelResolver.hasDeliverableChannel(userId)) {
            log.info("User {} has a planning cron but no deliverable channel; skipping schedule", userId)
            return
        }
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
            val resolved = channelResolver.resolve(userId)
            if (resolved == null) {
                log.warn("User {} has no deliverable channel; cannot start scheduled session", userId)
                return
            }
            val channel: ConversationChannel = resolved.channel

            val targetWeek = computeTargetWeek(userId)
            val existing = planningSessionService.findSessionForWeek(userId, targetWeek)
            if (existing != null) {
                when (existing.status) {
                    PlanningSessionStatus.ACTIVE -> {
                        log.info("Scheduled run for user {}: session already active for week {}, skipping", userId, targetWeek)
                    }
                    PlanningSessionStatus.COMPLETED -> {
                        // User pre-planned this week; surface the plan instead of starting fresh.
                        val summary = existing.summary
                        if (!summary.isNullOrBlank()) {
                            channel.send(ChannelMessage.Text(summary))
                        }
                    }
                    PlanningSessionStatus.ABANDONED -> {
                        val sessionId = orchestrator.start(userId, channel, targetWeek)
                        resolved.onSessionStarted(sessionId)
                    }
                }
                return
            }

            val sessionId = orchestrator.start(userId, channel, targetWeek)
            resolved.onSessionStarted(sessionId)
        } catch (e: Exception) {
            log.error("Failed to run scheduled planning session for user {}", userId, e)
        }
    }

    private fun computeTargetWeek(userId: UUID): LocalDate {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(clock.withZone(zone))
        val weekStartDay = WeekResolver.parseWeekStartDay(settings.weekStartDay)
        // Cron usually fires the night before the week starts; default to the upcoming week.
        // If today happens to be weekStartDay itself, the current-week resolution lands on today.
        val offset = if (today.dayOfWeek == (weekStartDay ?: java.time.DayOfWeek.MONDAY)) {
            WeekOffset.CURRENT
        } else {
            WeekOffset.NEXT
        }
        return WeekResolver.resolveWeekStart(today, weekStartDay, offset)
    }
}

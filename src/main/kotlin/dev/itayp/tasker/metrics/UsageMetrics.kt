package dev.itayp.tasker.metrics

import dev.itayp.tasker.ai.access.AiTier
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level usage gauges (users, feature opt-in, tasks, planning sessions) exposed to
 * Prometheus alongside the framework defaults. Counts are read from the DB on a fixed schedule into
 * in-memory holders; the gauges read the holders, so a scrape never hits the database and a query
 * failure never breaks scraping. Refresh cadence is coarse on purpose — these are slow-moving totals.
 */
@Component
class UsageMetrics(
    private val meterRegistry: MeterRegistry,
    private val userRepository: UserRepository,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val planningSessionRepository: PlanningSessionRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(UsageMetrics::class.java)

    private val totalUsers = AtomicLong(0)
    private val unclaimedUsers = AtomicLong(0)
    private val engagedUnclaimedUsers = AtomicLong(0)
    private val activeUsers = AtomicLong(0)
    private val tasksByStatus = TaskStatus.entries.associateWith { AtomicLong(0) }
    private val sessionsByStatus = PlanningSessionStatus.entries.associateWith { AtomicLong(0) }

    /** A user count split by `claimed`, so demo accounts (which keep the defaults) don't drown out real users. */
    private class ClaimedSplitGauge(val name: String, val description: String, val count: (claimed: Boolean) -> Long) {
        val holders = listOf(true, false).associateWith { AtomicLong(0) }
    }

    private val claimedSplitGauges = listOf(
        ClaimedSplitGauge("tasker.users.ai_enabled", "Users with AI switched on in their settings and a tier that grants it") {
            userSettingsRepository.countAiEnabled(it, AiTier.NONE.tierName)
        },
        ClaimedSplitGauge(
            "tasker.users.planning_scheduled",
            "Users with a weekly planning schedule set (it only runs where a channel can reach them)",
        ) { userSettingsRepository.countPlanningScheduled(it) },
        ClaimedSplitGauge("tasker.users.daily_digest_enabled", "Users with the daily digest switched on") {
            userSettingsRepository.countDailyDigestEnabled(it)
        },
    )

    @PostConstruct
    fun bind() {
        Gauge.builder("tasker.users.total", totalUsers) { it.get().toDouble() }
            .description("Total registered users")
            .register(meterRegistry)
        Gauge.builder("tasker.users.unclaimed", unclaimedUsers) { it.get().toDouble() }
            .description("Accounts with no login identity yet (not claimed)")
            .register(meterRegistry)
        Gauge.builder("tasker.users.engaged_unclaimed", engagedUnclaimedUsers) { it.get().toDouble() }
            .description("Unclaimed accounts that did real work (anonymous -> engaged -> claimed funnel)")
            .register(meterRegistry)
        Gauge.builder("tasker.users.active", activeUsers) { it.get().toDouble() }
            .description("Claimed users with activity in the last $ACTIVE_WINDOW_DAYS days")
            .register(meterRegistry)
        tasksByStatus.forEach { (status, holder) ->
            Gauge.builder("tasker.tasks.total", holder) { it.get().toDouble() }
                .description("Backlog tasks by status")
                .tag("status", status.name.lowercase())
                .register(meterRegistry)
        }
        sessionsByStatus.forEach { (status, holder) ->
            Gauge.builder("tasker.planning.sessions.total", holder) { it.get().toDouble() }
                .description("Planning sessions by status")
                .tag("status", status.name.lowercase())
                .register(meterRegistry)
        }
        claimedSplitGauges.forEach { gauge ->
            gauge.holders.forEach { (claimed, holder) ->
                Gauge.builder(gauge.name, holder) { it.get().toDouble() }
                    .description(gauge.description)
                    .tag("claimed", claimed.toString())
                    .register(meterRegistry)
            }
        }

        // Populate immediately so the first scrape after startup isn't all zeros.
        refresh()
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    fun refresh() {
        try {
            totalUsers.set(userRepository.count())
            unclaimedUsers.set(userRepository.countByClaimed(false))
            engagedUnclaimedUsers.set(userRepository.countByClaimedAndEngagedAtNotNull(false))
            activeUsers.set(userRepository.countByClaimedAndLastActiveAtAfter(true, clock.instant().minus(Duration.ofDays(ACTIVE_WINDOW_DAYS))))
            tasksByStatus.forEach { (status, holder) -> holder.set(backlogTaskRepository.countByStatus(status)) }
            sessionsByStatus.forEach { (status, holder) -> holder.set(planningSessionRepository.countByStatus(status)) }
            claimedSplitGauges.forEach { gauge ->
                gauge.holders.forEach { (claimed, holder) -> holder.set(gauge.count(claimed)) }
            }
        } catch (e: Exception) {
            log.warn("Failed to refresh usage metrics", e)
        }
    }

    companion object {
        private const val ACTIVE_WINDOW_DAYS = 30L
    }
}

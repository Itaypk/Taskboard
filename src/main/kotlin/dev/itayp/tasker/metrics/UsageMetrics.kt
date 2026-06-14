package dev.itayp.tasker.metrics

import dev.itayp.tasker.ai.conversation.ConversationRepository
import dev.itayp.tasker.ai.conversation.ConversationStatus
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level usage gauges (users, tasks, planning sessions, AI conversations) exposed to
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
    private val conversationRepository: ConversationRepository,
) {
    private val log = LoggerFactory.getLogger(UsageMetrics::class.java)

    private val totalUsers = AtomicLong(0)
    private val unclaimedUsers = AtomicLong(0)
    private val engagedUnclaimedUsers = AtomicLong(0)
    private val tasksByStatus = TaskStatus.entries.associateWith { AtomicLong(0) }
    private val sessionsByStatus = PlanningSessionStatus.entries.associateWith { AtomicLong(0) }
    private val activeConversations = AtomicLong(0)

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
        Gauge.builder("tasker.ai.conversations.active", activeConversations) { it.get().toDouble() }
            .description("Active (not soft-deleted) AI conversations")
            .register(meterRegistry)

        // Populate immediately so the first scrape after startup isn't all zeros.
        refresh()
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    fun refresh() {
        try {
            totalUsers.set(userRepository.count())
            unclaimedUsers.set(userRepository.countByClaimed(false))
            engagedUnclaimedUsers.set(userRepository.countByClaimedAndEngagedAtNotNull(false))
            tasksByStatus.forEach { (status, holder) -> holder.set(backlogTaskRepository.countByStatus(status)) }
            sessionsByStatus.forEach { (status, holder) -> holder.set(planningSessionRepository.countByStatus(status)) }
            activeConversations.set(conversationRepository.countByStatus(ConversationStatus.ACTIVE))
        } catch (e: Exception) {
            log.warn("Failed to refresh usage metrics", e)
        }
    }
}

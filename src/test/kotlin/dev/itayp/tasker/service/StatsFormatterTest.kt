package dev.itayp.tasker.service

import dev.itayp.tasker.model.UserStats
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsFormatterTest {

    private val messageSource = ReloadableResourceBundleMessageSource().apply {
        setBasename("classpath:messages")
        setDefaultEncoding("UTF-8")
    }
    private val formatter = StatsFormatter(messageSource)

    private val zone = ZoneOffset.UTC

    @Test
    fun `empty snapshot renders header plus the empty message`() {
        val stats = UserStats(
            joinedAt = null,
            openTasks = 0,
            completedTasks = 0,
            planningSessions = 0,
            avgTasksCreatedPerWeek = 0.0,
            avgTasksCompletedPerWeek = 0.0,
            avgCompletion = null,
        )

        val lines = formatter.format(stats, Locale.ENGLISH, zone)

        assertEquals(2, lines.size)
        assertEquals("📊 Your stats", lines.first())
        assertTrue(lines[1].startsWith("No stats yet"))
    }

    @Test
    fun `populated snapshot renders one localized line per metric`() {
        val stats = UserStats(
            joinedAt = Instant.parse("2026-01-15T10:00:00Z"),
            openTasks = 3,
            completedTasks = 12,
            planningSessions = 4,
            avgTasksCreatedPerWeek = 2.5,
            avgTasksCompletedPerWeek = 1.0,
            avgCompletion = Duration.ofDays(3),
        )

        val lines = formatter.format(stats, Locale.ENGLISH, zone)

        assertEquals("📊 Your stats", lines.first())
        assertEquals(7, lines.size) // header, joined, open, completed, per-week, completion, sessions
        assertTrue(lines.any { it == "Joined Jan 15, 2026" }, "join date line: $lines")
        assertTrue(lines.any { it == "Open tasks: 3" })
        assertTrue(lines.any { it == "Completed tasks: 12" })
        assertTrue(lines.any { it.contains("2.5") && it.contains("1") }, "per-week line: $lines")
        assertTrue(lines.any { it == "Avg. time to complete a task: 3 days" }, "completion line: $lines")
        assertTrue(lines.any { it == "Planning sessions: 4" })
    }

    @Test
    fun `sub-day completion time is rendered in hours`() {
        val stats = populated(avgCompletion = Duration.ofHours(5))

        val lines = formatter.format(stats, Locale.ENGLISH, zone)

        assertTrue(lines.any { it == "Avg. time to complete a task: 5 hours" }, "completion line: $lines")
    }

    @Test
    fun `missing completion time falls back to the none message`() {
        val stats = populated(avgCompletion = null)

        val lines = formatter.format(stats, Locale.ENGLISH, zone)

        assertTrue(lines.any { it.contains("Not enough completed tasks") }, "completion line: $lines")
    }

    private fun populated(avgCompletion: Duration?) = UserStats(
        joinedAt = Instant.parse("2026-01-15T10:00:00Z"),
        openTasks = 3,
        completedTasks = 12,
        planningSessions = 4,
        avgTasksCreatedPerWeek = 2.5,
        avgTasksCompletedPerWeek = 1.0,
        avgCompletion = avgCompletion,
    )
}

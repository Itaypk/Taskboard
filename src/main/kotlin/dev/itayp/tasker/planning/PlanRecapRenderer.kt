package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders a finalized plan for a chat message: the scheduled tasks (with done marks and slot times,
 * read live from `planned_task` and the backlog) followed by the assistant's notes. Every surface
 * that quotes a plan back to the user goes through here, so the schedule shown is always the real
 * one, including slot moves and additions made outside a revise session.
 */
@Component
class PlanRecapRenderer(
    private val plannedTaskService: PlannedTaskService,
    private val backlogTaskService: BacklogTaskService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) {

    /** Tasks, then notes (when there are any), as one block. */
    fun render(session: PlanningSession, formatter: MessageFormatter): String {
        val locale = userSettingsService.getLocale(session.userId)
        val tasks = renderTasks(session, formatter, locale)
            ?: messageSource.getMessage("plan.recap.empty", null, locale)
        val notes = renderNotes(session, formatter, locale) ?: return tasks
        return "$tasks\n\n$notes"
    }

    /**
     * One line per scheduled task: a done mark, the live title (the planning-time title if the task
     * is gone), and its slots in the user's zone. Null when nothing is scheduled.
     */
    fun renderTasks(session: PlanningSession, formatter: MessageFormatter, locale: Locale): String? {
        val planned = plannedTaskService.findForSession(session.userId, session.id)
        if (planned.isEmpty()) return null
        val live = backlogTaskService
            .findTasksAcrossBoards(session.userId, planned.map { it.taskId })
            .associateBy { it.id }
        val zone = zoneOf(session)
        return planned.joinToString("\n") { task ->
            val backlogTask = live[task.taskId]
            val mark = if (backlogTask?.status == TaskStatus.DONE) DONE_MARK else OPEN_MARK
            val title = formatter.escape(backlogTask?.title ?: task.title)
            val slots = task.slots.mapNotNull { formatSlot(it, zone, locale) }
            if (slots.isEmpty()) "$mark $title" else "$mark $title — ${formatter.escape(slots.joinToString(", "))}"
        }
    }

    /** The week note and the revision notes, under a "Notes" label. Null when there are none. */
    fun renderNotes(session: PlanningSession, formatter: MessageFormatter, locale: Locale): String? {
        if (!session.hasNotes) return null
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val zone = zoneOf(session)
        val lines = buildList {
            session.weekNote?.let { add(formatter.escape(it)) }
            if (session.revisionNotes.isNotEmpty()) {
                add(formatter.bulletList(session.revisionNotes.map { note ->
                    "${note.at.atZone(zone).toLocalDate().format(dateFormat)}: ${note.text}"
                }))
            }
        }
        val label = messageSource.getMessage("plan.recap.notes", null, locale)
        return formatter.bold(label) + "\n" + lines.joinToString("\n")
    }

    private fun formatSlot(slot: AgreedTimeSlot, zone: ZoneId, locale: Locale): String? {
        val start = runCatching { OffsetDateTime.parse(slot.startIso).atZoneSameInstant(zone) }.getOrNull() ?: return null
        val end = runCatching { OffsetDateTime.parse(slot.endIso).atZoneSameInstant(zone) }.getOrNull()
        val day = start.format(DateTimeFormatter.ofPattern("EEE", locale))
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return if (end == null) "$day ${start.format(time)}" else "$day ${start.format(time)}–${end.format(time)}"
    }

    private fun zoneOf(session: PlanningSession): ZoneId =
        runCatching { ZoneId.of(userSettingsService.getOrCreate(session.userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))

    private companion object {
        const val DONE_MARK = "✅"
        const val OPEN_MARK = "▫️"
    }
}

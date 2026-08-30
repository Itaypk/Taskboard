package dev.itayp.tasker.capture

import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.ClarificationExchange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The per-conversation state of a quick-add ("/add") capture flow. Held by a channel-specific
 * registry (e.g. the Telegram registry keyed by chat id) and advanced by [QuickAddFlow]. The flow
 * is otherwise stateless; all continuity lives here. [createdAt] backs the registry's idle TTL.
 *
 * `originalRequest` and `clarifications` are threaded through every state so a later free-text
 * adjustment can be re-drafted with the full context of what the user originally asked for and any
 * clarifying answers they already gave. Each capture carries a list of [CapturedItem]s — a single
 * request may produce one or several tasks and/or one-off events.
 */
sealed interface QuickAddState {
    val createdAt: Instant

    /** Opened by a bare `/add` with no text: waiting for the user to describe the capture. */
    data class AwaitingDescription(
        override val createdAt: Instant,
    ) : QuickAddState

    /** A draft card is shown; the user can Save, Cancel, Adjust, or type a free-text adjustment. */
    data class AwaitingConfirmation(
        val items: List<CapturedItem>,
        val originalRequest: String,
        val clarifications: List<ClarificationExchange>,
        override val createdAt: Instant,
        /**
         * The model read the request as being for this week's plan. Carried only this far: it is
         * one half of the gate on the offer that follows a save (`docs/FREE-TEXT-CAPTURE.md` D6),
         * and it is re-read off every fresh draft, so an adjustment can turn it on or off.
         */
        val planThisWeek: Boolean = false,
    ) : QuickAddState

    /** The user tapped "Adjust"; waiting for them to say what to change. */
    data class AwaitingAdjustment(
        val items: List<CapturedItem>,
        val originalRequest: String,
        val clarifications: List<ClarificationExchange>,
        override val createdAt: Instant,
    ) : QuickAddState

    /**
     * The agent asked a clarifying question. [op] remembers whether we were drafting from scratch
     * or revising an existing draft, so the answer is re-applied to the right operation. [rounds] is
     * how many clarifications have been asked in this capture (capped by [QuickAddFlow.MAX_CLARIFY_ROUNDS]).
     */
    data class AwaitingClarification(
        val op: PendingOp,
        val question: String,
        val options: List<ClarifyOption>,
        val rounds: Int,
        override val createdAt: Instant,
    ) : QuickAddState

    /**
     * The task is saved and the user has been offered a day for it in this week's plan
     * (`docs/FREE-TEXT-CAPTURE.md` D6). Everything the hand-off needs is captured here, so nothing
     * has to be re-derived when the taps come back: [taskId] and [title] identify the backlog task
     * to slot, [minutes] is its duration, [sessionId] is the plan it goes into, and [days] is
     * exactly what was offered — the option ids are matched against it rather than re-computed, so
     * a tap can never land on a day that was never on screen.
     *
     * Unlike every other state this one is *passive*: the task is already saved, so anything the
     * user types instead of tapping is a new message rather than an answer, and the offer lapses.
     */
    data class AwaitingPlanDay(
        val taskId: UUID,
        val title: String,
        val minutes: Long,
        val sessionId: UUID,
        val days: List<LocalDate>,
        override val createdAt: Instant,
    ) : QuickAddState

    /** A day was picked; waiting for the coarse time of day. As passive as [AwaitingPlanDay]. */
    data class AwaitingPlanTime(
        val taskId: UUID,
        val title: String,
        val minutes: Long,
        val sessionId: UUID,
        val day: LocalDate,
        val times: List<PlanTimeOfDay>,
        override val createdAt: Instant,
    ) : QuickAddState
}

/**
 * The coarse times of day the plan hand-off offers, in place of the web UI's free time input —
 * Telegram has buttons, not a clock (`docs/FREE-TEXT-CAPTURE.md` D6). Times are in the user's own
 * zone; a slot that has already passed today isn't offered.
 */
enum class PlanTimeOfDay(val at: LocalTime) {
    MORNING(LocalTime.of(9, 0)),
    AFTERNOON(LocalTime.of(14, 0)),
    EVENING(LocalTime.of(19, 0)),
    ;

    /** Message key for this option's button label; takes the localized clock time as `{0}`. */
    val labelKey: String get() = "quickadd.plan.time.${name.lowercase()}"

    companion object {
        fun parse(raw: String?): PlanTimeOfDay? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/** The drafting operation a clarification belongs to, so its answer re-runs the same call. */
sealed interface PendingOp {
    val originalRequest: String
    val clarifications: List<ClarificationExchange>

    data class Draft(
        override val originalRequest: String,
        override val clarifications: List<ClarificationExchange>,
    ) : PendingOp

    data class Revise(
        override val originalRequest: String,
        val items: List<CapturedItem>,
        val instruction: String,
        override val clarifications: List<ClarificationExchange>,
    ) : PendingOp
}

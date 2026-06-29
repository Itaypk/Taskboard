package dev.itayp.tasker.capture

import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.ClarificationExchange
import java.time.Instant

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

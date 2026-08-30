package dev.itayp.tasker.capture

import dev.itayp.tasker.planning.CaptureIntent

/**
 * What opening a capture turned into.
 *
 * Only the *unprompted* entry points ([QuickAddFlow.beginUnprompted],
 * [QuickAddFlow.beginUnpromptedFromMedia]) can return [Routed] — a message the user sent with no
 * `/add` in front of it may turn out not to be a capture at all, and the channel decides where it
 * goes instead. `/add` and every round inside a live capture can only produce [Captured]
 * (`docs/FREE-TEXT-CAPTURE.md` D1–D3).
 *
 * The flow stays channel-agnostic by naming the destination rather than dispatching to it: mapping
 * a [CaptureIntent] onto a bot command (or a web equivalent) is the channel's job.
 */
sealed interface CaptureEntry {

    /**
     * The message was handled as a capture. [state] is the flow state to store, or null when the
     * flow is already over — saved, cancelled, rate-limited, or failed.
     */
    data class Captured(val state: QuickAddState?) : CaptureEntry

    /**
     * The message wasn't a capture; the channel routes it to [intent]. [text] is the message that
     * was routed — for a voice note, what the model heard in it — so a destination that opens a
     * conversation about it can carry it in instead of asking the user to repeat themselves
     * (`docs/FREE-TEXT-CAPTURE.md` D3a).
     */
    data class Routed(val intent: CaptureIntent, val text: String) : CaptureEntry

    /**
     * The flow state, for callers that cannot be routed. [Routed] is unreachable for them — the
     * model is never offered that shape outside an unprompted entry — and degrades to "the flow is
     * over" rather than an exception if that ever stops holding.
     */
    fun stateOrNull(): QuickAddState? = (this as? Captured)?.state
}

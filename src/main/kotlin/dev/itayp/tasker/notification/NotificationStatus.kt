package dev.itayp.tasker.notification

enum class NotificationStatus {
    /** Queued, not yet fired. */
    PENDING,

    /** Successfully delivered to the user's channel. */
    SENT,

    /** Cancelled before firing — e.g. the originating slot was removed or rescheduled. */
    CANCELLED,

    /** Was still PENDING when its slot's start time passed; a "before-start" nudge is now useless. */
    EXPIRED,

    /** Came due but was deliberately not delivered — user opted out, no channel, or the task is gone. */
    SKIPPED,

    /** Delivery failed repeatedly and hit the retry cap. */
    FAILED,
}

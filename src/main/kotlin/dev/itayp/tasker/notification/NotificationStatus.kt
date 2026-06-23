package dev.itayp.tasker.notification

enum class NotificationStatus {
    /** Queued, not yet fired. */
    PENDING,

    /** Successfully emitted to the handler layer. */
    SENT,

    /** Cancelled before firing — e.g. the originating slot was removed or rescheduled. */
    CANCELLED,

    /** Was still PENDING when its slot's start time passed; a "before-start" nudge is now useless. */
    EXPIRED,
}

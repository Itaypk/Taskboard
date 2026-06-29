package dev.itayp.tasker.oneoff

import java.time.Instant

/**
 * Input for [OneOffEventService.createEvents]. Times are absolute instants; rendering for the
 * calendar invite resolves the user's timezone at send time so a draft drafted on one device and
 * confirmed on another stays correct.
 */
data class OneOffEventDraft(
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val location: String? = null,
    val notes: String? = null,
)

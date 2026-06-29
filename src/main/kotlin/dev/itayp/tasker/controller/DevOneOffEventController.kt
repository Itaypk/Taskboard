package dev.itayp.tasker.controller

import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventDraft
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardMembershipService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Dev-only endpoint to exercise the one-off event persist + invite path before the AI changes
 * land. Posts a batch of drafts (mirroring what the quick-add flow will eventually hand to
 * [OneOffEventService]) onto the user's default board.
 */
@RestController
@RequestMapping("/api/dev/one-off-events")
@Profile("dev")
class DevOneOffEventController(
    private val oneOffEventService: OneOffEventService,
    private val boardMembershipService: BoardMembershipService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: CreateOneOffEventsRequest,
    ): ResponseEntity<CreateOneOffEventsResponse> {
        val boardId = boardMembershipService.resolveDefaultBoard(principal.userId)
        val drafts = request.events.map { it.toDraft() }
        val result = oneOffEventService.createEvents(principal.userId, boardId, drafts)
        return ResponseEntity.ok(CreateOneOffEventsResponse(result.events.map { it.toView() }))
    }
}

data class CreateOneOffEventsRequest(val events: List<OneOffEventDraftRequest>)

data class OneOffEventDraftRequest(
    val title: String,
    /** ISO-8601 with offset, e.g. `2026-07-15T19:30:00+03:00`. */
    val startsAt: String,
    /** ISO-8601 with offset. */
    val endsAt: String,
    val location: String? = null,
    val notes: String? = null,
) {
    fun toDraft(): OneOffEventDraft = OneOffEventDraft(
        title = title,
        startsAt = OffsetDateTime.parse(startsAt).toInstant(),
        endsAt = OffsetDateTime.parse(endsAt).toInstant(),
        location = location,
        notes = notes,
    )
}

data class CreateOneOffEventsResponse(val events: List<OneOffEventView>)

data class OneOffEventView(
    val id: UUID,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val location: String?,
    val notes: String?,
    val icalUid: String,
)

private fun OneOffEvent.toView(): OneOffEventView = OneOffEventView(
    id = id,
    title = title,
    startsAt = startsAt,
    endsAt = endsAt,
    location = location,
    notes = notes,
    icalUid = icalUid,
)

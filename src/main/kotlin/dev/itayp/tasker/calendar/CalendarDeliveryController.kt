package dev.itayp.tasker.calendar

import dev.itayp.tasker.security.TaskerPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CalendarFeedLinkResponse(
    val url: String,
    val webcalUrl: String,
    val googleUrl: String,
    val createdAt: String,
    val lastFetchedAt: String?,
)

data class CalendarDeliveryResponse(
    /** `NONE`, `EMAIL` or `FEED`. */
    val delivery: String,
    val emailAvailable: Boolean,
    val feed: CalendarFeedLinkResponse?,
)

data class UpdateCalendarDeliveryRequest(val delivery: CalendarDelivery)

data class CalendarDeliverySwitchPreviewResponse(val invitationsToCancel: Int, val invitationsToSend: Int)

/**
 * The calendar card in Settings › Integrations (GitHub issue #346). On the session chain, with
 * CSRF. Saves on its own rather than through the settings form, because a switch has side effects
 * (cancelling or sending invitations) that the UI confirms first.
 */
@RestController
@RequestMapping("/api/v1/calendar-delivery")
class CalendarDeliveryController(
    private val calendarDeliveryService: CalendarDeliveryService,
    private val calendarFeedService: CalendarFeedService,
) {

    @GetMapping
    fun get(@AuthenticationPrincipal principal: TaskerPrincipal): CalendarDeliveryResponse =
        calendarDeliveryService.getState(principal.userId).toResponse()

    /** Switches delivery. 400 with `code: CALENDAR_INVITES_UNAVAILABLE` for EMAIL without a verified email. */
    @PutMapping
    fun change(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: UpdateCalendarDeliveryRequest,
    ): CalendarDeliveryResponse =
        calendarDeliveryService.change(principal.userId, request.delivery).toResponse()

    /** What switching to [delivery] would do to invitations, for the confirmation. */
    @GetMapping("/switch-preview")
    fun preview(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam delivery: CalendarDelivery,
    ): CalendarDeliverySwitchPreviewResponse =
        calendarDeliveryService.preview(principal.userId, delivery).let {
            CalendarDeliverySwitchPreviewResponse(it.invitationsToCancel, it.invitationsToSend)
        }

    /** Replaces the feed link; the old one stops working at once. */
    @PostMapping("/feed/rotate")
    fun rotate(@AuthenticationPrincipal principal: TaskerPrincipal): CalendarDeliveryResponse {
        calendarFeedService.rotate(principal.userId)
        return calendarDeliveryService.getState(principal.userId).toResponse()
    }
}

private fun CalendarDeliveryState.toResponse() = CalendarDeliveryResponse(
    delivery = delivery.name,
    emailAvailable = emailAvailable,
    feed = feed?.let {
        CalendarFeedLinkResponse(
            url = it.url,
            webcalUrl = it.webcalUrl,
            googleUrl = it.googleUrl,
            createdAt = it.createdAt.toString(),
            lastFetchedAt = it.lastFetchedAt?.toString(),
        )
    },
)

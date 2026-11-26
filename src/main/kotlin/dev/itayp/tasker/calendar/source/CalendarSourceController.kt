package dev.itayp.tasker.calendar.source

import dev.itayp.tasker.security.TaskerPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CalendarSourceResponse(
    val id: UUID,
    val label: String,
    val host: String,
    /** `TITLES` or `BUSY`. */
    val detail: CalendarSourceDetail,
    val createdAt: String,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    /** An [IcsFetchError] name when the last read failed. */
    val lastError: IcsFetchError?,
    /** Events in the seven days after the last successful read. */
    val lastEventCount: Int?,
)

data class AddCalendarSourceRequest(
    @field:NotBlank(message = "Paste the calendar's address.")
    @field:Size(max = 2048, message = "That address is too long.")
    val url: String,
    @field:Size(max = 200, message = "That name is too long.")
    val label: String? = null,
    val detail: CalendarSourceDetail = CalendarSourceDetail.TITLES,
)

data class UpdateCalendarSourceRequest(
    @field:Size(max = 200, message = "That name is too long.")
    val label: String? = null,
    val detail: CalendarSourceDetail? = null,
)

/**
 * Settings › Calendar › "Let the planner see my calendar" (GitHub issue #347). Session chain,
 * with CSRF; personal, so not board-scoped. Saves on its own, like the rest of the calendar pane.
 * Refusals are 400 (429 for the rate limit) with a `code` the SPA localizes.
 */
@RestController
@RequestMapping("/api/v1/calendar-sources")
class CalendarSourceController(private val service: CalendarSourceService) {

    @GetMapping
    fun list(@AuthenticationPrincipal principal: TaskerPrincipal): List<CalendarSourceResponse> =
        service.list(principal.userId).map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: AddCalendarSourceRequest,
    ): CalendarSourceResponse =
        service.add(principal.userId, request.url, request.label, request.detail).toResponse()

    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateCalendarSourceRequest,
    ): CalendarSourceResponse =
        service.update(principal.userId, id, request.label, request.detail).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: TaskerPrincipal, @PathVariable id: UUID) =
        service.delete(principal.userId, id)

    @PostMapping("/{id}/refresh")
    fun refresh(@AuthenticationPrincipal principal: TaskerPrincipal, @PathVariable id: UUID): CalendarSourceResponse =
        service.refresh(principal.userId, id).toResponse()

    private fun CalendarSourceView.toResponse() = CalendarSourceResponse(
        id = id,
        label = label,
        host = host,
        detail = detail,
        createdAt = createdAt.toString(),
        lastAttemptAt = lastAttemptAt?.toString(),
        lastSuccessAt = lastSuccessAt?.toString(),
        lastError = lastError,
        lastEventCount = lastEventCount,
    )
}

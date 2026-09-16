package dev.itayp.tasker.controller

import dev.itayp.tasker.planning.NoPlannableTasksException
import dev.itayp.tasker.service.BlockedEmailDomainException
import dev.itayp.tasker.service.InvalidRecurrenceException
import dev.itayp.tasker.service.InvalidTaskUrlException
import dev.itayp.tasker.service.UnclaimedAccountCapExceededException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns bean-validation failures on `@Valid @RequestBody` DTOs into an RFC 7807 [ProblemDetail]
 * (`application/problem+json`) instead of Spring's default error page. `detail` is the first
 * offending field's human-readable reason — the DTO constraint messages are written as complete
 * sentences, so it reads cleanly in a toast. The per-field breakdown rides along in the standard
 * extension-property map under `errors`, so a form can highlight the matching input inline.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    /** One offending field, carried in the ProblemDetail `errors` extension property. */
    data class FieldError(val field: String, val message: String)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val errors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage ?: "is invalid") }
        val detail = errors.firstOrNull()?.message ?: "Some of those values are not valid."
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply {
            title = "Validation failed"
            setProperty("errors", errors)
        }
    }

    /**
     * Handled here (rather than per-controller) so every entry point that checks the email
     * blocklist — magic-link login, settings email change, board invitations — surfaces the same
     * clean response.
     * The exception's own `@ResponseStatus` reason wouldn't reach the client on its own
     * (`server.error.include-message` defaults to `never`), so we set `detail` explicitly.
     */
    @ExceptionHandler(BlockedEmailDomainException::class)
    fun handleBlockedEmailDomain(ex: BlockedEmailDomainException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "This email domain isn't allowed").apply {
            title = "Email domain not allowed"
            // `detail` is English-only; the SPA keys off this code to show a localized message.
            setProperty("code", "BLOCKED_EMAIL_DOMAIN")
        }

    /** Same reasoning as [handleBlockedEmailDomain]: set `detail` explicitly so it reaches the client. */
    @ExceptionHandler(UnclaimedAccountCapExceededException::class)
    fun handleUnclaimedAccountCapExceeded(ex: UnclaimedAccountCapExceededException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message!!).apply {
            title = "Sign-ups temporarily unavailable"
        }

    /**
     * Same reasoning as [handleBlockedEmailDomain]. The SPA greys out its start buttons off
     * `plannableTaskCount`, so a user should rarely see this; it catches a stale client.
     */
    @ExceptionHandler(NoPlannableTasksException::class)
    fun handleNoPlannableTasks(ex: NoPlannableTasksException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message!!).apply {
            title = "Nothing to plan"
            setProperty("code", "NO_PLANNABLE_TASKS")
        }

    /** Our own message (it names the offending field and range), so it's safe to echo. */
    @ExceptionHandler(InvalidRecurrenceException::class)
    fun handleInvalidRecurrence(ex: InvalidRecurrenceException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message!!).apply {
            title = "Invalid recurrence"
            setProperty("code", "INVALID_RECURRENCE")
        }

    /**
     * Carries the same `errors` shape bean validation produces, attributed to `url`: the rule only
     * moved into the service because it needs the stored value, and the task editor highlights the
     * offending input from this list.
     */
    @ExceptionHandler(InvalidTaskUrlException::class)
    fun handleInvalidTaskUrl(ex: InvalidTaskUrlException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message!!).apply {
            title = "Validation failed"
            setProperty("errors", listOf(FieldError("url", ex.message!!)))
        }

    /**
     * Bad client input that reaches a service as an [IllegalArgumentException] — most visibly
     * `TaskStatus.valueOf` / `TaskPriority.valueOf` in `BacklogTaskService`, whose DTO fields are
     * only length-constrained, so `{"status": "finished"}` used to surface as a 500. It is a
     * client error, so answer 400.
     *
     * The message is not echoed back: these come from arbitrary internal call sites and may name
     * internals. Callers that want a specific, actionable message (the agent API does) validate up
     * front and return their own ProblemDetail before reaching here.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        logger.warn("Rejected request with invalid argument: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Some of those values are not valid.",
        ).apply { title = "Invalid request" }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}

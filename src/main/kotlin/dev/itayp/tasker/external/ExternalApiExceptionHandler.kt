package dev.itayp.tasker.external

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Failures that never reach a controller method — a body Jackson can't read, a path variable that
 * isn't a UUID — answered as RFC 7807 instead of the framework default (a 400 with an empty body,
 * which tells an AI caller nothing it can act on).
 *
 * Scoped to [ExternalTaskController]'s package rather than added to the global
 * `ApiExceptionHandler`: the wording here is deliberately verbose and instructional for a model
 * reading the response, which is the wrong register for the SPA's toasts. It takes precedence over
 * the global advice for these controllers, but handles exception types the global advice doesn't
 * declare anyway, so nothing is shadowed.
 */
@RestControllerAdvice(basePackageClasses = [ExternalTaskController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ExternalApiExceptionHandler {

    /**
     * Covers both malformed JSON and a well-formed body whose values don't fit the DTO
     * (`{"estimatedMinutes": "soon"}`). Jackson's own message names the offending offset or field
     * path, so it is echoed — it describes the caller's own payload, never anything server-side —
     * trimmed to keep a runaway parser dump out of the response.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ProblemDetail {
        val reason = ex.mostSpecificCause.message
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_REASON_LENGTH)
        logger.debug("External API rejected an unreadable request body: {}", reason)
        val detail = buildString {
            append("Request body must be a JSON object matching the documented schema.")
            if (reason != null) append(" Parser said: $reason")
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
            .apply { title = "Malformed request body" }
    }

    /** A path or query value of the wrong type — in practice a task or board id that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        val expected = ex.requiredType?.simpleName ?: "value"
        val detail = "'${ex.name}' is not a valid $expected."
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
            .apply { title = "Invalid request" }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalApiExceptionHandler::class.java)
        private const val MAX_REASON_LENGTH = 200
    }
}

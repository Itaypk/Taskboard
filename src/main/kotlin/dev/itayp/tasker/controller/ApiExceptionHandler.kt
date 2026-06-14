package dev.itayp.tasker.controller

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
}

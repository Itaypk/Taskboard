package dev.itayp.tasker.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns bean-validation failures on `@Valid @RequestBody` DTOs into a structured 400 the SPA can
 * surface field-by-field, instead of Spring's default error page (which the frontend could only
 * render as a generic "that request looks invalid").
 *
 * `message` is the first offending field's human-readable reason — the DTO constraint messages are
 * written as complete sentences, so this reads cleanly in a toast. `fields` lists every offending
 * field so a form can highlight the matching input inline.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    data class FieldErrorDetail(val field: String, val message: String)

    data class ValidationErrorResponse(val message: String, val fields: List<FieldErrorDetail>)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ValidationErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.map { error ->
            FieldErrorDetail(error.field, error.defaultMessage ?: "is invalid")
        }
        val message = fields.firstOrNull()?.message ?: "Some of those values are not valid."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ValidationErrorResponse(message, fields))
    }
}

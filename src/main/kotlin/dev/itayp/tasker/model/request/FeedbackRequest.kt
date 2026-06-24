package dev.itayp.tasker.model.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * A piece of user feedback submitted from the in-app "Send feedback" form.
 * [replyEmail] is optional — the user only provides it if they want a reply.
 */
data class FeedbackRequest(
    @field:NotBlank(message = "Please enter some feedback.")
    @field:Size(max = 5000, message = "Feedback must be at most 5000 characters.")
    val message: String,
    /** Optional address the user wants a reply at; not required to submit feedback. */
    @field:Size(max = 320, message = "Email must be at most 320 characters.")
    @field:Email(message = "Please enter a valid email address.")
    @field:Pattern(regexp = "^[^\\r\\n]*$", message = "Email must not contain newlines.")
    val replyEmail: String? = null,
)

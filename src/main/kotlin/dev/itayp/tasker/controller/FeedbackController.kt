package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.FeedbackRequest
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.FeedbackService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * In-app feedback. Authenticated (reached from the signed-in profile menu), so CSRF protection
 * applies via the default session security chain. Submissions are emailed to the configured
 * recipient by [FeedbackService].
 */
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
) {

    @PostMapping
    fun submit(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: FeedbackRequest,
    ): ResponseEntity<Unit> {
        feedbackService.submit(principal.userId, request.message, request.replyEmail)
        return ResponseEntity.noContent().build()
    }
}

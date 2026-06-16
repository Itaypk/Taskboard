package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.InviteMemberRequest
import dev.itayp.tasker.model.response.PendingInvitationResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardInvitationService
import dev.itayp.tasker.service.InviterNotEligibleException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/boards/{boardId}/invitations")
class BoardInvitationController(private val invitationService: BoardInvitationService) {

    @PostMapping
    fun invite(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: InviteMemberRequest,
    ): ResponseEntity<PendingInvitationResponse> {
        val pending = invitationService.invite(principal.userId, boardId, request.email)
        return ResponseEntity.status(HttpStatus.CREATED).body(pending.toResponse())
    }

    @GetMapping
    fun listPending(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<List<PendingInvitationResponse>> =
        ResponseEntity.ok(invitationService.listPending(principal.userId, boardId).map { it.toResponse() })

    @DeleteMapping("/{invitationId}")
    fun revoke(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable invitationId: UUID,
    ): ResponseEntity<Void> {
        invitationService.revoke(principal.userId, boardId, invitationId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Surfaces the eligibility reason in the response body. The exception's own `@ResponseStatus`
     * reason wouldn't reach the client (`server.error.include-message` defaults to `never`), so we
     * return a ProblemDetail whose `detail` the SPA reads for the error toast.
     */
    @ExceptionHandler(InviterNotEligibleException::class)
    fun handleInviterNotEligible(ex: InviterNotEligibleException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message ?: "You need a registered login method to invite others")
}

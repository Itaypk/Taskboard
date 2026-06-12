package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.AcceptInvitationResponse
import dev.itayp.tasker.model.response.InvitationPreviewResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardInvitationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Token-scoped invitation endpoints, not board-scoped: the token *is* the authorization. The preview
 * is unauthenticated and side-effect-free (scanner-safe by construction); accept is an authenticated
 * POST — whoever is signed in joins (Decision 3).
 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController(private val invitationService: BoardInvitationService) {

    @GetMapping("/{token}")
    fun preview(@PathVariable token: String): ResponseEntity<InvitationPreviewResponse> =
        ResponseEntity.ok(invitationService.preview(token).toResponse())

    @PostMapping("/{token}/accept")
    fun accept(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable token: String,
    ): ResponseEntity<AcceptInvitationResponse> =
        ResponseEntity.ok(invitationService.accept(token, principal.userId).toResponse())
}

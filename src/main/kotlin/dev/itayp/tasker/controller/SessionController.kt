package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.ActiveSessionResponse
import dev.itayp.tasker.model.response.RevokeSessionsResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserSessionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * "Active sessions": lets a signed-in user see where their account is logged in and sign out
 * everywhere else. Both endpoints are authenticated and CSRF-protected — deliberately not on the
 * exempt list in [dev.itayp.tasker.config.SecurityConfiguration], because unlike the login
 * endpoints the user already has a session here.
 */
@RestController
@RequestMapping("/api/auth/sessions")
class SessionController(
    private val userSessionService: UserSessionService,
) {

    @GetMapping
    fun listSessions(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<List<ActiveSessionResponse>> =
        ResponseEntity.ok(userSessionService.list(principal.userId, request.getSession(false)?.id))

    @PostMapping("/revoke-others")
    fun revokeOthers(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<RevokeSessionsResponse> {
        val revoked = userSessionService.revokeOthers(principal.userId, request.getSession(false)?.id)
        return ResponseEntity.ok(RevokeSessionsResponse(revoked))
    }
}

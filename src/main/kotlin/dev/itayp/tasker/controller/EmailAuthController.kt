package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.EmailLoginRequest
import dev.itayp.tasker.model.request.TokenRequest
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.EmailLoginResult
import dev.itayp.tasker.service.EmailLoginService
import dev.itayp.tasker.util.localRedirect
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Passwordless email login. All endpoints are unauthenticated (they create the session):
 *  - POST /api/auth/email               — send a magic link; always 204, never reveals account existence.
 *  - GET  /api/auth/email/precheck      — side-effect-free validity check for the confirm page.
 *  - POST /api/auth/email/callback      — consume the token, create the session, return JSON outcome.
 *  - GET  /api/auth/email/callback      — backward-compat shim: 302-redirects old links to the confirm page.
 */
@RestController
@RequestMapping("/api/auth/email")
class EmailAuthController(
    private val emailLoginService: EmailLoginService,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    @PostMapping
    fun requestLogin(@Valid @RequestBody request: EmailLoginRequest): ResponseEntity<Unit> {
        emailLoginService.requestLogin(request.email, request.next)
        return ResponseEntity.noContent().build()
    }

    /** Side-effect-free validity check. Scanner prefetches are harmless here. */
    @GetMapping("/precheck")
    fun precheck(@RequestParam token: String): ResponseEntity<Map<String, Boolean>> {
        val valid = emailLoginService.precheckToken(token)
        return ResponseEntity.ok(mapOf("valid" to valid))
    }

    /**
     * Consumes the token and establishes a session. Returns JSON so the SPA confirm page can
     * navigate client-side rather than relying on a server-side redirect.
     */
    @PostMapping("/callback")
    fun callback(
        @Valid @RequestBody body: TokenRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>> {
        val outcome = when (val result = emailLoginService.completeLogin(body.token)) {
            is EmailLoginResult.Success -> {
                sessionAuthenticator.authenticate(TaskerPrincipal(result.user.id!!), request, response)
                "success"
            }
            EmailLoginResult.UnverifiedConflict -> "unverified"
            EmailLoginResult.Invalid -> "invalid"
        }
        return ResponseEntity.ok(mapOf("outcome" to outcome))
    }

    /**
     * Backward-compat shim for links sent before the confirm-page migration. Redirects to the
     * confirm page without consuming the token — the scanner-safety property holds because this
     * GET has no side effect either; the POST does the work.
     */
    @GetMapping("/callback")
    fun callbackShim(@RequestParam token: String): ResponseEntity<Unit> {
        val destination = localRedirect("/email-login?token=$token")
        return ResponseEntity.status(302).location(URI.create(destination)).build()
    }
}

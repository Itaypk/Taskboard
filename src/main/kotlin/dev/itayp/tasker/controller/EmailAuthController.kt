package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.UpdateEmailRequest
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.EmailLoginResult
import dev.itayp.tasker.service.EmailLoginService
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
 * Passwordless email login. Both endpoints are unauthenticated (they create the session):
 *  - POST /api/auth/email          — send a magic link; always 204, never reveals account existence.
 *  - GET  /api/auth/email/callback — consume the link, create the session, redirect into the app.
 */
@RestController
@RequestMapping("/api/auth/email")
class EmailAuthController(
    private val emailLoginService: EmailLoginService,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    @PostMapping
    fun requestLogin(@Valid @RequestBody request: UpdateEmailRequest): ResponseEntity<Unit> {
        emailLoginService.requestLogin(request.email)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Unit> {
        val location = when (val result = emailLoginService.completeLogin(token)) {
            is EmailLoginResult.Success -> {
                sessionAuthenticator.authenticate(TaskerPrincipal(result.user.id!!), request, response)
                "/"
            }
            EmailLoginResult.UnverifiedConflict -> "/?emailLogin=unverified"
            EmailLoginResult.Invalid -> "/?emailLogin=invalid"
        }
        return ResponseEntity.status(302).location(URI.create(localRedirect(location))).build()
    }

    /**
     * Defense-in-depth against open redirects: only ever redirect to a same-origin path of
     * our own. Today every [location] is a hard-coded relative path, but this guards against a
     * future change accidentally letting an absolute or protocol-relative URL through.
     */
    private fun localRedirect(path: String): String =
        if (path.startsWith("/") && !path.startsWith("//") && !path.contains('\\')) path else "/"
}

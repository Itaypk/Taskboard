package dev.itayp.tasker.controller

import dev.itayp.tasker.config.DEV_USER_ID
import dev.itayp.tasker.config.DEV_USER_TELEGRAM_ID
import dev.itayp.tasker.model.response.MeResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Dev-only bypass that skips the real Telegram flow. The bean is only created under
 * the `dev` profile, so the endpoint path is not mapped in prod.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("dev")
class DevAuthController(
    private val userAuthService: UserAuthService,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    @PostMapping("/dev-login")
    fun devLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val user = userAuthService.ensureDevUser(DEV_USER_ID, DEV_USER_TELEGRAM_ID)
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return ResponseEntity.ok(user.toMeResponse())
    }
}

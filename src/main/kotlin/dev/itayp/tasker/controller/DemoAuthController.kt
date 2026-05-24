package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.MeResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class DemoAuthController(
    private val userAuthService: UserAuthService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val userCrypto: UserCryptoService,
) {

    @PostMapping("/demo-login")
    fun demoLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val user = userAuthService.createDemoUser()
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        // Limit the session lifetime to match the demo data TTL (24 h)
        request.getSession(false)?.maxInactiveInterval = 24 * 60 * 60
        return ResponseEntity.ok(user.toMeResponse(userCrypto))
    }
}

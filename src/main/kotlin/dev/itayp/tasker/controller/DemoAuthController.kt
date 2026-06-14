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

    /**
     * Zero-registration start. Creates a real but unclaimed account (no login identity yet) and signs
     * the user in with a normal 30-day rolling session — no longer a throwaway 24 h demo. The user is
     * nudged in the UI to add an email or Telegram to keep their data; until then the account is
     * eligible for inactivity-based cleanup. See docs/DEMO-ACCOUNT-UNIFICATION.md.
     */
    @PostMapping("/demo-login")
    fun demoLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val user = userAuthService.createUnclaimedUser()
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return ResponseEntity.ok(user.toMeResponse(userCrypto))
    }
}

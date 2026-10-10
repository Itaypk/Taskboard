package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.MeResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.RegistrationHintsResolver
import dev.itayp.tasker.service.UserAuthService
import dev.itayp.tasker.service.UserSettingsService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class DemoAuthController(
    private val userAuthService: UserAuthService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val userCrypto: UserCryptoService,
    private val userSettingsService: UserSettingsService,
    private val registrationHintsResolver: RegistrationHintsResolver,
) {

    /**
     * Zero-registration start. Creates a real but unclaimed account (no login identity yet) and signs
     * the user in with a normal 30-day rolling session — no longer a throwaway 24 h demo. The user is
     * nudged in the UI to add an email or Telegram to keep their data; until then the account is
     * eligible for inactivity-based cleanup. See docs/DEMO-ACCOUNT-UNIFICATION.md.
     */
    @PostMapping("/demo-login")
    fun demoLogin(
        @RequestParam(required = false) lang: String?,
        @RequestParam(required = false) tz: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        // The demo is the funnel: a Hebrew-speaking visitor should see the demo in Hebrew, so
        // seed the language from the browser just like any other registration (docs/I18N.md, D2).
        // `lang` is what the visitor picked in the anonymous language switcher, and outranks the
        // header — otherwise picking Hebrew on an en-US browser yields a Hebrew page whose account
        // is English, and the UI snaps back to English the moment /me resolves.
        val hints = registrationHintsResolver.resolve(lang, request.getHeader("Accept-Language"), tz)
        val user = userAuthService.createUnclaimedUser(hints)
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return ResponseEntity.ok(user.toMeResponse(userCrypto, userSettingsService.getPreferredLanguage(user.id!!)))
    }
}

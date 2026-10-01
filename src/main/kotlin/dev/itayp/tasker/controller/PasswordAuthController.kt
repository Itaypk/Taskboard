package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.request.PasswordLoginRequest
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.LocalLoginService
import dev.itayp.tasker.service.LocaleNegotiationService
import dev.itayp.tasker.service.UserSettingsService
import dev.itayp.tasker.util.clientIp
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/auth/password` — sign in as one of the operator-listed local users
 * ([dev.itayp.tasker.config.AuthProperties.LocalUsers]). Unauthenticated and CSRF-exempt like the
 * other login endpoints: it creates the session rather than acting on one.
 */
@RestController
@RequestMapping("/api/auth")
class PasswordAuthController(
    private val localLoginService: LocalLoginService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val userCrypto: UserCryptoService,
    private val userSettingsService: UserSettingsService,
    private val localeNegotiationService: LocaleNegotiationService,
) {

    @PostMapping("/password")
    fun login(
        @Valid @RequestBody body: PasswordLoginRequest,
        @RequestParam(required = false) lang: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Any> {
        // Same answer whether local login is off, the username is unknown or the password is wrong.
        if (!localLoginService.enabled) return invalidCredentials()
        val localeHint = localeNegotiationService.resolveSupportedTag(lang, request.getHeader("Accept-Language"))
        val user = localLoginService.login(body.username, body.password, request.clientIp(), localeHint)
            ?: return invalidCredentials()
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return ResponseEntity.ok<Any>(user.toMeResponse(userCrypto, userSettingsService.getPreferredLanguage(user.id!!)))
    }

    // 400 rather than 401: the SPA treats any 401 as "your session expired" and resets auth state,
    // which is the wrong reaction to a mistyped password on the login form.
    private fun invalidCredentials(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body<Any>(
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Wrong username or password").apply {
                title = "Sign-in failed"
                setProperty("code", "INVALID_CREDENTIALS")
            },
        )
}

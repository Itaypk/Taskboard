package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.metrics.LoginMetrics
import dev.itayp.tasker.metrics.LoginMode
import dev.itayp.tasker.metrics.LoginOutcome
import dev.itayp.tasker.metrics.LoginProvider
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.security.google.GoogleAuthException
import dev.itayp.tasker.security.google.GoogleOidcService
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.GoogleLoginOutcome
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.RegistrationClosedException
import dev.itayp.tasker.service.RegistrationHintsResolver
import dev.itayp.tasker.service.UserAuthService
import dev.itayp.tasker.util.localRedirect
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * "Sign in with Google" via the OIDC Authorization Code + PKCE redirect flow; the same three
 * browser-navigation GETs as [TelegramOidcController]:
 *
 *  - `/start`      — begin a login; stash state+PKCE in session, 302 to Google.
 *  - `/link/start` — begin linking Google to the signed-in account (authenticated).
 *  - `/callback`   — Google returns here; exchange the code, validate the id_token, then either
 *                    create a session (login) or attach the identity (link).
 *
 * Outcomes the SPA should explain land as `?googleLogin=<code>` on `/` or `?googleLink=<code>` on
 * `/settings`.
 */
@RestController
@RequestMapping("/api/auth/google")
class GoogleOidcController(
    private val googleOidcService: GoogleOidcService,
    private val userAuthService: UserAuthService,
    private val accountLinkService: AccountLinkService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val registrationHintsResolver: RegistrationHintsResolver,
    private val appProperties: AppProperties,
    private val loginMetrics: LoginMetrics,
) {

    @GetMapping("/start")
    fun startLogin(
        @RequestParam(required = false) next: String?,
        @RequestParam(required = false) lang: String?,
        @RequestParam(required = false) tz: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!googleOidcService.isConfigured()) return redirect("/?googleLogin=unavailable")
        val authz = googleOidcService.buildAuthorizationRequest(redirectUri())
        // The callback is a redirect from Google, so the visitor's language pick and browser zone can
        // only reach it via the session. Validated here, so nothing unchecked is stored.
        val hints = registrationHintsResolver.resolve(lang, null, tz)
        roundTrip.begin(
            request.session, authz.state, authz.codeVerifier, OAuthMode.LOGIN,
            next = localRedirect(next), language = hints.language, timeZone = hints.timeZone,
        )
        return redirect(authz.authorizationUrl)
    }

    @GetMapping("/link/start")
    fun startLink(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!googleOidcService.isConfigured()) return redirect("/settings?googleLink=unavailable")
        val authz = googleOidcService.buildAuthorizationRequest(redirectUri())
        roundTrip.begin(request.session, authz.state, authz.codeVerifier, OAuthMode.LINK)
        return redirect(authz.authorizationUrl)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @AuthenticationPrincipal principal: TaskerPrincipal?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        val pending = roundTrip.consume(request.session, signedIn = principal != null)
        val isLink = pending.mode == OAuthMode.LINK
        val metricMode = if (isLink) LoginMode.LINK else LoginMode.LOGIN
        fun count(outcome: LoginOutcome) = loginMetrics.record(LoginProvider.GOOGLE, outcome, metricMode)

        val rejection = pending.rejection(code, state, error)
        if (rejection != null) {
            logger.warn("Google callback rejected (mode={}, signedIn={}): {}", pending.mode.value, principal != null, rejection)
            count(LoginOutcome.REJECTED)
            return failureRedirect(isLink)
        }

        val data = try {
            googleOidcService.completeAuthorization(code!!, pending.codeVerifier!!, redirectUri())
        } catch (e: GoogleAuthException) {
            logger.warn("Google callback auth failed (mode={}): {}", pending.mode.value, e.message)
            count(LoginOutcome.ERROR)
            return failureRedirect(isLink)
        }

        if (isLink) {
            if (principal == null) {
                count(LoginOutcome.REJECTED)
                return redirect("/?googleLink=expired")
            }
            val result = accountLinkService.linkGoogle(principal.userId, data)
            val linked = result == LinkResult.Success || result == LinkResult.AlreadyLinked
            count(if (linked) LoginOutcome.SUCCESS else LoginOutcome.REJECTED)
            return when (result) {
                LinkResult.Success, LinkResult.AlreadyLinked -> redirect("/settings?googleLink=success")
                LinkResult.ConflictOwnedByAnother -> redirect("/settings?googleLink=conflict")
                LinkResult.AlreadyHasProvider -> redirect("/settings?googleLink=exists")
            }
        }

        // Registration seeds the language and time zone from the browser that ran the round-trip.
        val hints = registrationHintsResolver.resolve(pending.language, request.getHeader("Accept-Language"), pending.timeZone)
        val outcome = try {
            userAuthService.loginByGoogle(data, hints)
        } catch (_: RegistrationClosedException) {
            logger.info("Google login refused: unknown identity and registration is closed")
            count(LoginOutcome.REJECTED)
            return redirect("/?googleLogin=closed")
        }
        return when (outcome) {
            is GoogleLoginOutcome.Success -> {
                sessionAuthenticator.authenticate(TaskerPrincipal(outcome.user.id!!), request, response)
                count(LoginOutcome.SUCCESS)
                redirect(localRedirect(pending.next))
            }
            GoogleLoginOutcome.EmailConflict -> {
                count(LoginOutcome.REJECTED)
                redirect("/?googleLogin=emailConflict")
            }
        }
    }

    // Trimmed: a base URL configured with a trailing slash would give Google "//api/...", which must match the registered URI exactly.
    private fun redirectUri(): String = "${appProperties.baseUrl.trimEnd('/')}/api/auth/google/callback"

    private fun failureRedirect(isLink: Boolean): ResponseEntity<Void> =
        redirect(if (isLink) "/settings?googleLink=failed" else "/?googleLogin=failed")

    private fun redirect(target: String): ResponseEntity<Void> = OAuthRoundTrip.redirect(target)

    companion object {
        private val logger = LoggerFactory.getLogger(GoogleOidcController::class.java)
        private val roundTrip = OAuthRoundTrip("googleOauth")
    }
}

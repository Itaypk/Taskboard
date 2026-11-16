package dev.itayp.tasker.controller

import dev.itayp.nescioquid.telegram.TelegramAuthException
import dev.itayp.nescioquid.telegram.TelegramOidcService
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.metrics.LoginMetrics
import dev.itayp.tasker.metrics.LoginMode
import dev.itayp.tasker.metrics.LoginOutcome
import dev.itayp.tasker.metrics.LoginProvider
import dev.itayp.tasker.model.response.TelegramBotResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.RegistrationClosedException
import dev.itayp.tasker.service.RegistrationHintsResolver
import dev.itayp.tasker.service.UserAuthService
import dev.itayp.tasker.util.localRedirect
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Telegram login via the OIDC Authorization Code + PKCE redirect flow. Replaces the legacy
 * Login Widget (iframe + HMAC). Three browser-navigation GETs, plus one JSON read:
 *
 *  - `/start`        — begin a login; stash state+PKCE in session, 302 to Telegram.
 *  - `/link/start`   — begin linking Telegram to the signed-in account (authenticated).
 *  - `/callback`     — Telegram returns here; we exchange the code, validate the id_token,
 *                      then either create a session (login) or attach the identity (link).
 *  - `/bot`          — the messaging bot's @username, for the post-link "open the chat" step.
 *
 * State/PKCE live in the HTTP session, so the callback is CSRF-safe (the `state` parameter is
 * the anti-forgery token) without needing the SPA's XSRF header — which a top-level redirect
 * from Telegram could not supply anyway.
 */
@RestController
@RequestMapping("/api/auth/telegram")
class TelegramOidcController(
    private val telegramOidcService: TelegramOidcService,
    private val userAuthService: UserAuthService,
    private val accountLinkService: AccountLinkService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val registrationHintsResolver: RegistrationHintsResolver,
    private val appProperties: AppProperties,
    private val loginMetrics: LoginMetrics,
    @Value("\${tasker.telegram.bot-username}") private val botUsername: String,
) {

    /**
     * The messaging bot's handle. Linking Telegram authenticates the account but does *not* create
     * a chat with the bot: Telegram refuses to let a bot write to someone who has never written to
     * it first ("[400] Bad Request: chat not found"), so a freshly-linked user is unreachable until
     * they open the chat themselves. The SPA needs this handle to walk them there, and gets it at
     * runtime rather than baked into the bundle so the value tracks the deployment's config.
     *
     * Authenticated by the `/api/..` catch-all — the only caller is the Settings screen.
     */
    @GetMapping("/bot")
    fun bot(): TelegramBotResponse = TelegramBotResponse(username = botUsername.ifBlank { null })

    @GetMapping("/start")
    fun startLogin(
        @RequestParam(required = false) next: String?,
        @RequestParam(required = false) lang: String?,
        @RequestParam(required = false) tz: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!telegramOidcService.isConfigured()) return redirect("/?telegramLogin=unavailable")
        val authz = telegramOidcService.buildAuthorizationRequest(redirectUri())
        // Only the explicit pick and the zone: the header is read at the callback, from the same browser.
        // The callback is a redirect from Telegram, so the visitor's language pick and browser zone can
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
        if (!telegramOidcService.isConfigured()) return redirect("/settings?telegramLink=unavailable")
        val authz = telegramOidcService.buildAuthorizationRequest(redirectUri())
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
        fun count(outcome: LoginOutcome) = loginMetrics.record(LoginProvider.TELEGRAM, outcome, metricMode)

        val rejection = pending.rejection(code, state, error)
        if (rejection != null) {
            logger.warn(
                "Telegram callback rejected (mode={}, signedIn={}): {}",
                pending.mode.value, principal != null, rejection,
            )
            count(LoginOutcome.REJECTED)
            return failureRedirect(isLink)
        }

        val data = try {
            telegramOidcService.completeAuthorization(code!!, pending.codeVerifier!!, redirectUri())
        } catch (e: TelegramAuthException) {
            logger.warn("Telegram callback auth failed (mode={}): {}", pending.mode.value, e.message)
            count(LoginOutcome.ERROR)
            return failureRedirect(isLink)
        }

        if (isLink) {
            if (principal == null) {
                count(LoginOutcome.REJECTED)
                return redirect("/?telegramLink=expired")
            }
            val result = accountLinkService.linkTelegram(principal.userId, data)
            val linked = result == LinkResult.Success || result == LinkResult.AlreadyLinked
            count(if (linked) LoginOutcome.SUCCESS else LoginOutcome.REJECTED)
            return when (result) {
                LinkResult.Success, LinkResult.AlreadyLinked -> redirect("/settings?telegramLink=success")
                LinkResult.ConflictOwnedByAnother -> redirect("/settings?telegramLink=conflict")
                LinkResult.AlreadyHasProvider -> redirect("/settings?telegramLink=exists")
            }
        }

        // Registration seeds the language and time zone from the browser that ran the OAuth round-trip;
        // an existing user's stored settings are untouched (the hints are ignored on login).
        val hints = registrationHintsResolver.resolve(pending.language, request.getHeader("Accept-Language"), pending.timeZone)
        val user = try {
            userAuthService.loginOrRegisterByTelegram(data, hints)
        } catch (_: RegistrationClosedException) {
            logger.info("Telegram login refused: unknown identity and registration is closed")
            count(LoginOutcome.REJECTED)
            return redirect("/?telegramLogin=closed")
        }
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        count(LoginOutcome.SUCCESS)
        return redirect(localRedirect(pending.next))
    }

    private fun redirectUri(): String = "${appProperties.baseUrl}/api/auth/telegram/callback"

    private fun failureRedirect(isLink: Boolean): ResponseEntity<Void> =
        redirect(if (isLink) "/settings?telegramLink=failed" else "/?telegramLogin=failed")

    private fun redirect(target: String): ResponseEntity<Void> = OAuthRoundTrip.redirect(target)

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramOidcController::class.java)
        private val roundTrip = OAuthRoundTrip("tgOauth")
    }
}

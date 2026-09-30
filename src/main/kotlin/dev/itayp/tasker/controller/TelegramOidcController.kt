package dev.itayp.tasker.controller

import dev.itayp.nescioquid.telegram.TelegramAuthException
import dev.itayp.nescioquid.telegram.TelegramOidcService
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.model.response.TelegramBotResponse
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.LocaleNegotiationService
import dev.itayp.tasker.service.RegistrationClosedException
import dev.itayp.tasker.service.UserAuthService
import dev.itayp.tasker.util.localRedirect
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

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
    private val localeNegotiationService: LocaleNegotiationService,
    private val appProperties: AppProperties,
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
    fun startLogin(@RequestParam(required = false) next: String?, request: HttpServletRequest): ResponseEntity<Void> {
        if (!telegramOidcService.isConfigured()) return redirect("/?telegramLogin=unavailable")
        val authz = telegramOidcService.buildAuthorizationRequest(redirectUri())
        request.session.apply {
            setAttribute(ATTR_STATE, authz.state)
            setAttribute(ATTR_VERIFIER, authz.codeVerifier)
            setAttribute(ATTR_MODE, MODE_LOGIN)
            setAttribute(ATTR_NEXT, localRedirect(next))
        }
        return redirect(authz.authorizationUrl)
    }

    @GetMapping("/link/start")
    fun startLink(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!telegramOidcService.isConfigured()) return redirect("/settings?telegramLink=unavailable")
        val authz = telegramOidcService.buildAuthorizationRequest(redirectUri())
        request.session.apply {
            setAttribute(ATTR_STATE, authz.state)
            setAttribute(ATTR_VERIFIER, authz.codeVerifier)
            setAttribute(ATTR_MODE, MODE_LINK)
        }
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
        val session = request.session
        val mode = session.getAttribute(ATTR_MODE) as? String ?: MODE_LOGIN
        val expectedState = session.getAttribute(ATTR_STATE) as? String
        val verifier = session.getAttribute(ATTR_VERIFIER) as? String
        val next = session.getAttribute(ATTR_NEXT) as? String ?: "/"
        clearOauthAttributes(session)

        val isLink = mode == MODE_LINK
        if (!error.isNullOrBlank() || code.isNullOrBlank() || state.isNullOrBlank() || verifier == null) {
            logger.warn("Telegram callback rejected (mode={}): error={}, missing code/state/verifier", mode, error)
            return failureRedirect(isLink)
        }
        if (expectedState == null || state != expectedState) {
            logger.warn("Telegram callback state mismatch (mode={})", mode)
            return failureRedirect(isLink)
        }

        val data = try {
            telegramOidcService.completeAuthorization(code, verifier, redirectUri())
        } catch (e: TelegramAuthException) {
            logger.warn("Telegram callback auth failed (mode={}): {}", mode, e.message)
            return failureRedirect(isLink)
        }

        if (isLink) {
            if (principal == null) return redirect("/?telegramLink=expired")
            return when (accountLinkService.linkTelegram(principal.userId, data)) {
                LinkResult.Success, LinkResult.AlreadyLinked -> redirect("/settings?telegramLink=success")
                LinkResult.ConflictOwnedByAnother -> redirect("/settings?telegramLink=conflict")
                LinkResult.AlreadyHasProvider -> redirect("/settings?telegramLink=exists")
            }
        }

        // Registration seeds preferred_language from the browser that ran the OAuth round-trip;
        // an existing user's stored preference is untouched (the hint is ignored on login).
        val localeHint = localeNegotiationService.resolveSupportedTag(request.getHeader("Accept-Language"))
        val user = try {
            userAuthService.loginOrRegisterByTelegram(data, localeHint)
        } catch (_: RegistrationClosedException) {
            logger.info("Telegram login refused: unknown identity and registration is closed")
            return redirect("/?telegramLogin=closed")
        }
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return redirect(localRedirect(next))
    }

    private fun redirectUri(): String = "${appProperties.baseUrl}/api/auth/telegram/callback"

    private fun failureRedirect(isLink: Boolean): ResponseEntity<Void> =
        redirect(if (isLink) "/settings?telegramLink=failed" else "/?telegramLogin=failed")

    // Authorization URLs are absolute (Telegram); SPA notices are same-origin paths run through localRedirect.
    private fun redirect(target: String): ResponseEntity<Void> {
        val location = if (target.startsWith("http")) target else localRedirect(target)
        return ResponseEntity.status(302).location(URI.create(location)).build()
    }

    private fun clearOauthAttributes(session: HttpSession) {
        listOf(ATTR_STATE, ATTR_VERIFIER, ATTR_MODE, ATTR_NEXT).forEach(session::removeAttribute)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramOidcController::class.java)
        private const val ATTR_STATE = "tgOauthState"
        private const val ATTR_VERIFIER = "tgOauthVerifier"
        private const val ATTR_MODE = "tgOauthMode"
        private const val ATTR_NEXT = "tgOauthNext"
        private const val MODE_LOGIN = "login"
        private const val MODE_LINK = "link"
    }
}

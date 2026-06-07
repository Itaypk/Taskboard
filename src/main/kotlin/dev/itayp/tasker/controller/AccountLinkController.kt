package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.LinkedIdentityResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.TelegramAuthException
import dev.itayp.tasker.service.TelegramAuthService
import dev.itayp.tasker.service.UnlinkResult
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * "Connected accounts": link additional login methods to the signed-in user, list them, and
 * unlink. All endpoints require authentication (they fall under the session chain's `/api/**`
 * rule) and the mutating ones are CSRF-protected — unlike the login endpoints, the user is
 * already signed in here.
 */
@RestController
@RequestMapping("/api/auth")
class AccountLinkController(
    private val accountLinkService: AccountLinkService,
    private val telegramAuthService: TelegramAuthService,
    private val userRepository: UserRepository,
    private val userCrypto: UserCryptoService,
) {

    @GetMapping("/identities")
    fun listIdentities(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<List<LinkedIdentityResponse>> {
        val identities = accountLinkService.listIdentities(principal.userId).map {
            LinkedIdentityResponse(
                provider = it.provider ?: "",
                linkedAt = it.createdAt?.toString(),
                lastLoginAt = it.lastLoginAt?.toString(),
            )
        }
        return ResponseEntity.ok(identities)
    }

    @PostMapping("/link/telegram")
    fun linkTelegram(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody payload: Map<String, String>,
    ): ResponseEntity<Any> {
        val data = telegramAuthService.verify(payload)
        return when (accountLinkService.linkTelegram(principal.userId, data)) {
            LinkResult.Success, LinkResult.AlreadyLinked -> {
                val user = userRepository.findById(principal.userId).orElseThrow()
                ResponseEntity.ok<Any>(user.toMeResponse(userCrypto))
            }
            LinkResult.ConflictOwnedByAnother ->
                ResponseEntity.status(409).body<Any>(mapOf("error" to "telegram_owned_by_another_account"))
            LinkResult.AlreadyHasProvider ->
                ResponseEntity.status(409).body<Any>(mapOf("error" to "telegram_already_linked"))
        }
    }

    @DeleteMapping("/identities/{provider}")
    fun unlink(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable provider: String,
    ): ResponseEntity<Any> {
        return when (accountLinkService.unlink(principal.userId, provider)) {
            UnlinkResult.Success -> ResponseEntity.noContent().build<Any>()
            UnlinkResult.NotLinked -> ResponseEntity.status(404).body<Any>(mapOf("error" to "not_linked"))
            UnlinkResult.WouldRemoveLastMethod ->
                ResponseEntity.status(409).body<Any>(mapOf("error" to "last_login_method"))
        }
    }

    @ExceptionHandler(TelegramAuthException::class)
    fun handleTelegramAuthFailure(ex: TelegramAuthException): ResponseEntity<Map<String, String>> {
        logger.warn("Telegram link auth rejected: {}", ex.message)
        return ResponseEntity.status(401).body(mapOf("error" to "telegram_auth_failed"))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AccountLinkController::class.java)
    }
}

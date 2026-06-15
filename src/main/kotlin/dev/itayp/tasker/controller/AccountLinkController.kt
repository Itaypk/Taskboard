package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.LinkedIdentityResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.UnlinkResult
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * "Connected accounts": list the signed-in user's login methods and unlink them. All endpoints
 * require authentication. Linking a Telegram account is handled by the OIDC redirect flow in
 * [TelegramOidcController] (`/api/auth/telegram/link/start`), since linking now needs the same
 * Telegram round-trip as logging in.
 */
@RestController
@RequestMapping("/api/auth")
class AccountLinkController(
    private val accountLinkService: AccountLinkService,
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
}

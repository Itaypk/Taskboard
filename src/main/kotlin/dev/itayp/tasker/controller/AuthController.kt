package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.response.MeResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Account-status endpoint. Telegram login moved to the OIDC redirect flow in
 * [TelegramOidcController]; other login methods live in their own controllers.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val userCrypto: UserCryptoService,
    private val userSettingsService: UserSettingsService,
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: TaskerPrincipal?): ResponseEntity<MeResponse> {
        if (principal == null) return ResponseEntity.noContent().build()
        val user: UserEntity = userRepository.findById(principal.userId).orElse(null)
            ?: return ResponseEntity.noContent().build()
        val language = userSettingsService.getPreferredLanguage(principal.userId)
        return ResponseEntity.ok(user.toMeResponse(userCrypto, language))
    }
}

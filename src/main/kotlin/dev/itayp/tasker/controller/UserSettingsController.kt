package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.UpdateEmailRequest
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.SettingsOptionsResponse
import dev.itayp.tasker.model.response.UserSettingsResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.EmailVerificationService
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration

@RestController
@RequestMapping("/api/v1/settings")
class UserSettingsController(
    private val userSettingsService: UserSettingsService,
    private val emailVerificationService: EmailVerificationService,
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(UserSettingsController::class.java)

    @GetMapping
    fun getSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<UserSettingsResponse> {
        val settings = userSettingsService.getOrCreate(principal.userId)
        val user = userRepository.findById(principal.userId).orElseThrow()
        return ResponseEntity.ok(settings.toResponse(user))
    }

    @PutMapping
    fun updateSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        return try {
            val settings = userSettingsService.update(principal.userId, request)
            val user = userRepository.findById(principal.userId).orElseThrow()
            ResponseEntity.ok(settings.toResponse(user))
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid settings update from user {}: {}", principal.userId, e.message)
            ResponseEntity.badRequest().build()
        }
    }

    @PostMapping("/email")
    fun requestEmailVerification(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: UpdateEmailRequest,
    ): ResponseEntity<Unit> {
        emailVerificationService.requestVerification(principal.userId, request.email)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/email/verify")
    fun verifyEmail(@RequestParam token: String): ResponseEntity<Unit> {
        emailVerificationService.confirmVerification(token)
        return ResponseEntity.status(302).location(URI.create("/?emailVerified=true")).build()
    }

    @GetMapping("/options")
    fun getOptions(): ResponseEntity<SettingsOptionsResponse> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .body(
                SettingsOptionsResponse(
                    timeZones = UserSettingsService.SUPPORTED_TIME_ZONES,
                    languages = UserSettingsService.SUPPORTED_LANGUAGES,
                    genders = UserSettingsService.SUPPORTED_GENDERS,
                )
            )
}

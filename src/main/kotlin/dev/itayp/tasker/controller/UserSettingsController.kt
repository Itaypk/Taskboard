package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.access.AiUsageSummary
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.request.DetectedTimeZoneRequest
import dev.itayp.tasker.model.request.TokenRequest
import dev.itayp.tasker.model.request.UpdateEmailRequest
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.SettingsOptionsResponse
import dev.itayp.tasker.model.response.UserSettingsResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.EmailAlreadyLinkedException
import dev.itayp.tasker.service.EmailVerificationService
import dev.itayp.tasker.service.UserSettingsService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
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
    private val userCrypto: UserCryptoService,
    private val aiAccessService: AiAccessService,
) {

    private val log = LoggerFactory.getLogger(UserSettingsController::class.java)

    @GetMapping
    fun getSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<UserSettingsResponse> {
        val settings = userSettingsService.getOrCreate(principal.userId)
        val user = userRepository.findById(principal.userId).orElseThrow()
        val email = userCrypto.decrypt(principal.userId, user.email)
        return ResponseEntity.ok(settings.toResponse(email, user.emailVerifiedAt != null))
    }

    @GetMapping("/ai-usage")
    fun getAiUsage(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<AiUsageSummary> =
        ResponseEntity.ok(aiAccessService.usageSummaryFor(principal.userId))

    @PutMapping
    fun updateSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> {
        return try {
            val settings = userSettingsService.update(principal.userId, request)
            val user = userRepository.findById(principal.userId).orElseThrow()
            val email = userCrypto.decrypt(principal.userId, user.email)
            ResponseEntity.ok(settings.toResponse(email, user.emailVerifiedAt != null))
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid settings update from user {}: {}", principal.userId, e.message)
            ResponseEntity.badRequest().build()
        }
    }

    /**
     * The browser's time zone, reported once by the SPA for a new account (`timeZoneDetectionPending`
     * on `/me`). Always 204: the service applies it only while detection is pending and the zone is
     * supported, and the SPA has nothing to do differently either way.
     */
    @PostMapping("/time-zone/detected")
    fun reportDetectedTimeZone(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: DetectedTimeZoneRequest,
    ): ResponseEntity<Unit> {
        userSettingsService.applyDetectedTimeZone(principal.userId, request.timeZone)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/email")
    fun requestEmailVerification(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: UpdateEmailRequest,
    ): ResponseEntity<Unit> {
        emailVerificationService.requestVerification(principal.userId, request.email)
        return ResponseEntity.noContent().build()
    }

    /**
     * Consumes an email-verification token. Unauthenticated — the token is the credential; the
     * user may open this link on a different device from the one they're logged in on.
     */
    @PostMapping("/email/verify")
    fun verifyEmail(@Valid @RequestBody body: TokenRequest): ResponseEntity<Map<String, Boolean>> {
        val success = emailVerificationService.confirmVerification(body.token)
        return ResponseEntity.ok(mapOf("success" to success))
    }

    /**
     * Backward-compat shim for verification links sent before the confirm-page migration.
     * Redirects to the confirm page without consuming the token.
     */
    @GetMapping("/email/verify")
    fun verifyEmailShim(@RequestParam token: String): ResponseEntity<Unit> =
        ResponseEntity.status(302)
            .location(URI.create("/email-verify?token=$token"))
            .build()

    @ExceptionHandler(EmailAlreadyLinkedException::class)
    fun handleEmailAlreadyLinked(ex: EmailAlreadyLinkedException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(409).body(mapOf("error" to "email_owned_by_another_account"))

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

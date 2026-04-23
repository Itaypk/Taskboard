package dev.itayp.tasker.controller

import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.response.MeResponse
import dev.itayp.tasker.model.response.toMeResponse
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.TelegramAuthException
import dev.itayp.tasker.service.TelegramAuthService
import dev.itayp.tasker.service.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val telegramAuthService: TelegramAuthService,
    private val userAuthService: UserAuthService,
    private val userRepository: UserRepository,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    @PostMapping("/telegram")
    fun telegramLogin(
        @RequestBody payload: Map<String, String>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val data = telegramAuthService.verify(payload)
        val user = userAuthService.loginOrRegisterByTelegram(data)
        sessionAuthenticator.authenticate(TaskerPrincipal(user.id!!), request, response)
        return ResponseEntity.ok(user.toMeResponse())
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<MeResponse> {
        val user: UserEntity = userRepository.findById(principal.userId).orElse(null)
            ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(user.toMeResponse())
    }

    @ExceptionHandler(TelegramAuthException::class)
    fun handleTelegramAuthFailure(ex: TelegramAuthException): ResponseEntity<Map<String, String>> {
        logger.warn("Telegram auth rejected: {}", ex.message)
        return ResponseEntity.status(401).body(mapOf("error" to "telegram_auth_failed"))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthController::class.java)
    }
}

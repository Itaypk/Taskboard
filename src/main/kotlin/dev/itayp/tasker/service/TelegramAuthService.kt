package dev.itayp.tasker.service

import dev.itayp.tasker.config.TelegramAuthProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies Telegram Login Widget payloads per
 * https://core.telegram.org/widgets/login#checking-authorization.
 *
 * auth_date freshness: 60s tolerance limits the replay window for a leaked payload.
 */
@Service
class TelegramAuthService(
    private val properties: TelegramAuthProperties,
    private val clock: Clock,
) {

    fun verify(payload: Map<String, String>): TelegramAuthData {
        if (properties.botToken.isBlank()) {
            throw TelegramAuthException("Telegram bot token is not configured")
        }

        val hash = payload["hash"] ?: throw TelegramAuthException("Missing hash")

        val dataCheckString = payload.asSequence()
            .filter { it.key != "hash" }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val secretKey = MessageDigest.getInstance("SHA-256")
            .digest(properties.botToken.toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretKey, "HmacSHA256"))
        }
        val computed = mac.doFinal(dataCheckString.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        if (!MessageDigest.isEqual(
                computed.toByteArray(StandardCharsets.UTF_8),
                hash.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            throw TelegramAuthException("Bad HMAC")
        }

        val authDateSeconds = payload["auth_date"]?.toLongOrNull()
            ?: throw TelegramAuthException("Missing auth_date")
        val ageSeconds = clock.instant().epochSecond - authDateSeconds
        if (ageSeconds < -MAX_FUTURE_SKEW_SECONDS || ageSeconds > MAX_AGE_SECONDS) {
            throw TelegramAuthException("Stale auth_date")
        }

        val telegramId = payload["id"]?.toLongOrNull()
            ?: throw TelegramAuthException("Missing id")

        return TelegramAuthData(
            telegramId = telegramId,
            username = payload["username"],
            firstName = payload["first_name"],
            photoUrl = payload["photo_url"],
            authDate = Instant.ofEpochSecond(authDateSeconds),
        )
    }

    companion object {
        private const val MAX_AGE_SECONDS = 60L
        private const val MAX_FUTURE_SKEW_SECONDS = 30L
    }
}

data class TelegramAuthData(
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val photoUrl: String?,
    val authDate: Instant,
)

class TelegramAuthException(message: String) : RuntimeException(message)

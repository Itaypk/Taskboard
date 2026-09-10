package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Locale
import java.util.UUID

/**
 * Resolves whether — and how — a user will actually receive calendar-invite emails for the time
 * blocks agreed in a planning session. A user with email integration disabled globally, the
 * calendar-invite setting off, or no verified email address receives nothing over this channel.
 *
 * Shared by [PlanFinalizationService] (to decide whether to dispatch invites), `OneOffEventService`
 * (same, for one-off events), and `dev.itayp.tasker.notification.NotificationChannelSummary` (to
 * describe delivery for the planning prompt, alongside the independent Telegram reminder channel)
 * so the eligibility gate lives in exactly one place.
 */
@Component
class InviteDeliveryResolver(
    private val userRepository: UserRepository,
    private val userSettingsService: UserSettingsService,
    private val userCrypto: UserCryptoService,
    private val emailProperties: EmailProperties,
) {
    private val log = LoggerFactory.getLogger(InviteDeliveryResolver::class.java)

    data class EmailContext(val email: String, val locale: Locale)

    /** Non-null when calendar invites can be delivered to the user; null otherwise (with a debug reason). */
    fun resolveEmailContext(userId: UUID): EmailContext? {
        if (!emailProperties.enabled) {
            log.debug("Skipping calendar invites: email integration disabled")
            return null
        }
        val settings = userSettingsService.getOrCreate(userId)
        if (!settings.calendarInviteEmail) {
            log.debug("Skipping calendar invites: calendarInviteEmail disabled for user {}", userId)
            return null
        }
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (user.emailVerifiedAt == null) {
            log.debug("Skipping calendar invites: email not verified for user {}", userId)
            return null
        }
        val email = userCrypto.decrypt(userId, user.email)
        if (email.isNullOrBlank()) {
            log.debug("Skipping calendar invites: no email on file for user {}", userId)
            return null
        }
        return EmailContext(email, userSettingsService.getLocale(userId))
    }
}

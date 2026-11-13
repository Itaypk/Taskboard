package dev.itayp.tasker.planning

import dev.itayp.tasker.calendar.CalendarDelivery
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
 * blocks agreed in a planning session. A user with email integration disabled globally, calendar
 * delivery set to anything but EMAIL, or no verified email address receives nothing over this channel.
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
        val delivery = userSettingsService.getOrCreate(userId).calendarDelivery
        if (delivery != CalendarDelivery.EMAIL) {
            log.debug("Skipping calendar invites: calendar delivery is {} for user {}", delivery, userId)
            return null
        }
        return resolveEmailTarget(userId)
    }

    /**
     * Where invitations would go if the user chose them, whatever calendar delivery is set to now:
     * null when email is off on this instance or the user has no verified address. Lets a switch to
     * invitations be refused up front, and lets a switch away from them still reach the inbox.
     */
    fun resolveEmailTarget(userId: UUID): EmailContext? {
        if (!emailProperties.enabled) {
            log.debug("Skipping calendar invites: email integration disabled")
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

    /**
     * True when the user's blocks reach their calendar through the subscription feed instead: the
     * feed serves whatever the plan holds, so there is nothing to dispatch, but the block does reach
     * the calendar (callers use this to avoid telling the user that "no invite was sent").
     */
    fun deliversToFeed(userId: UUID): Boolean =
        userSettingsService.getOrCreate(userId).calendarDelivery == CalendarDelivery.FEED
}

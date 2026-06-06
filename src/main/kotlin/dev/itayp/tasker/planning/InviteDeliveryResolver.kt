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
 * Resolves whether — and how — a user will actually receive the time blocks agreed in a planning
 * session. Today the only delivery channel is calendar-invitation email; a user with email
 * integration disabled globally, the calendar-invite setting off, or no verified email address
 * receives nothing.
 *
 * Shared by [PlanFinalizationService] (to decide whether to dispatch invites) and the planning
 * prompt assembler (to tell the assistant what reminders the user can expect) so the eligibility
 * gate lives in exactly one place.
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

    /**
     * Human-readable description of how the user will (or won't) receive agreed time blocks, injected
     * into the planning prompt so the assistant can set expectations — and warn the user when nothing
     * is wired up rather than implying reminders that will never arrive.
     */
    fun describeDeliveryMethods(userId: UUID): String {
        val ctx = resolveEmailContext(userId)
        return if (ctx != null) {
            "Agreed time blocks are sent to the user as calendar invitations by email (to ${ctx.email}); " +
                "accepting an invite adds that block to their calendar. This is currently their only " +
                "reminder channel."
        } else {
            "The user has NO active reminder/notification delivery method: time blocks you agree on are " +
                "saved to their weekly plan in the app, but they will receive NO calendar invitation or " +
                "reminder for them. Don't imply that scheduling a task will notify them. If they'd like " +
                "reminders, they can enable calendar-invite email with a verified address in settings — " +
                "you may mention this once if it's relevant, but don't nag."
        }
    }
}

package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Resolves whether — and how — an app-driven reminder will actually reach a user, the single home of
 * the delivery gate (mirrors `planning.InviteDeliveryResolver` for calendar invites). A reminder is
 * delivered only when the user has app reminders enabled *and* a deliverable push channel; the channel
 * gate is why "app reminders on by default" only ever notifies users who can actually receive it.
 */
@Component
class ReminderDeliveryResolver(
    private val userSettingsService: UserSettingsService,
    private val channelResolver: ScheduledConversationChannelResolver,
) {
    private val log = LoggerFactory.getLogger(ReminderDeliveryResolver::class.java)

    data class ReminderContext(
        val channel: ConversationChannel,
        val locale: Locale,
        val zone: ZoneId,
        /**
         * The user-level gate for AI-generated reminder copy: AI is on AND they haven't opted out of
         * enhanced reminders. The dispatcher still applies the per-board AI veto (a shared-board
         * co-member opt-out) before actually calling the model.
         */
        val aiEnhanced: Boolean,
    )

    /** Non-null when a reminder can be delivered to the user; null otherwise (with a debug reason). */
    fun resolve(userId: UUID): ReminderContext? {
        val settings = userSettingsService.getOrCreate(userId)
        if (!settings.appReminders) {
            log.debug("Skipping app reminder: appReminders disabled for user {}", userId)
            return null
        }
        val resolved = channelResolver.resolve(userId)
        if (resolved == null) {
            log.debug("Skipping app reminder: no deliverable channel for user {}", userId)
            return null
        }
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        return ReminderContext(
            channel = resolved.channel,
            locale = Locale.forLanguageTag(settings.preferredLanguage),
            zone = zone,
            aiEnhanced = settings.aiEnabled && settings.aiEnhancedReminders,
        )
    }
}

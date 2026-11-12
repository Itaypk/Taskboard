package dev.itayp.tasker.notification

import dev.itayp.tasker.planning.InviteDeliveryResolver
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Human-readable description of how a user will (or won't) be reminded about the time blocks
 * agreed in a planning session, combining the two independent delivery channels —
 * [InviteDeliveryResolver] (calendar-invite email or the subscription feed) and [ReminderDeliveryResolver] (Telegram
 * slot reminder) — so the planning prompt can tell the assistant the whole truth rather than
 * just the email half of it. Injected into the weekly-planning system prompt as
 * `{{delivery_methods}}`; the assistant is instructed to acknowledge this in its closing
 * message, so getting it right (and not silently dropping a working channel) matters.
 */
@Component
class NotificationChannelSummary(
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val reminderDeliveryResolver: ReminderDeliveryResolver,
) {

    fun describe(userId: UUID): String {
        val emailContext = inviteDeliveryResolver.resolveEmailContext(userId)
        val viaFeed = emailContext == null && inviteDeliveryResolver.deliversToFeed(userId)
        val reminderEligible = reminderDeliveryResolver.resolve(userId) != null

        return when {
            emailContext != null && reminderEligible ->
                "Agreed time blocks are sent to the user as calendar invitations by email (to " +
                    "${emailContext.email}); accepting an invite adds that block to their calendar. " +
                    "They will ALSO get a Telegram reminder shortly before each block starts. Both " +
                    "channels are active."

            emailContext != null ->
                "Agreed time blocks are sent to the user as calendar invitations by email (to " +
                    "${emailContext.email}); accepting an invite adds that block to their calendar. " +
                    "They will NOT get a Telegram reminder — either they haven't linked/enabled " +
                    "Telegram, or they've turned app reminders off. Calendar invite email is their " +
                    "only reminder channel."

            viaFeed && reminderEligible ->
                "Agreed time blocks appear in a calendar the user has subscribed to (no emails are " +
                    "sent); their calendar app picks up changes on its own schedule, so a block may take " +
                    "a while to show up. They will ALSO get a Telegram reminder shortly before each " +
                    "block starts."

            viaFeed ->
                "Agreed time blocks appear in a calendar the user has subscribed to (no emails are " +
                    "sent); their calendar app picks up changes on its own schedule, so a block may take " +
                    "a while to show up. They will NOT get a Telegram reminder — either they haven't " +
                    "linked/enabled Telegram, or they've turned app reminders off. Whatever alerts their " +
                    "calendar app gives are their only reminder."

            reminderEligible ->
                "The user will get a Telegram reminder shortly before each agreed block starts. " +
                    "They will NOT receive a calendar invitation, and the blocks don't reach their " +
                    "calendar at all — either they haven't verified an email address, or they've turned " +
                    "calendar delivery off. The Telegram reminder is their only reminder channel."

            else ->
                "The user has NO active reminder/notification delivery method: time blocks you agree " +
                    "on are saved to their weekly plan in the app, but they will receive NO calendar " +
                    "invitation and NO Telegram reminder for them. Don't imply that scheduling a task " +
                    "will notify them."
        }
    }
}

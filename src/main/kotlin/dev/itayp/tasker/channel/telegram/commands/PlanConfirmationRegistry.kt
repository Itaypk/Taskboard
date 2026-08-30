package dev.itayp.tasker.channel.telegram.commands

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class PlanConfirmationRegistry {

    private val pending = ConcurrentHashMap<Long, PendingConfirmation>()

    data class PendingConfirmation(
        val userId: UUID,
        /** Non-null when there is an active in-memory session to abandon if the user picks a "replan" option. */
        val existingSessionId: UUID?,
        /** Set when there's an existing session whose week should be reused on replan; null when the user must pick. */
        val replanWeekStart: LocalDate? = null,
        /** Non-null when there is a COMPLETED session the user can choose to revise in place. */
        val revisableSessionId: UUID? = null,
    )

    companion object {
        const val OPTION_KEEP = "plan_keep"
        const val OPTION_THIS_WEEK = "plan_this_week"
        const val OPTION_NEXT_WEEK = "plan_next_week"
        const val OPTION_REVISE = "plan_revise"

        /** Only offered when planning was *inferred* from a free-text message, never for `/plan`. */
        const val OPTION_DISMISS = "plan_dismiss"
    }

    fun set(chatId: Long, confirmation: PendingConfirmation) {
        pending[chatId] = confirmation
    }

    fun get(chatId: Long): PendingConfirmation? = pending[chatId]

    fun remove(chatId: Long) {
        pending.remove(chatId)
    }
}

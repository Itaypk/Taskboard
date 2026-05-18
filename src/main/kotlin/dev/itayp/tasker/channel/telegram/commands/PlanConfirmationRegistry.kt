package dev.itayp.tasker.channel.telegram.commands

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class PlanConfirmationRegistry {

    private val pending = ConcurrentHashMap<Long, PendingConfirmation>()

    data class PendingConfirmation(
        val userId: UUID,
        /** Non-null when there is an active in-memory session to abandon if the user picks [OPTION_NEW]. */
        val existingSessionId: UUID?,
    )

    companion object {
        const val OPTION_KEEP = "plan_keep"
        const val OPTION_NEW = "plan_new"
    }

    fun set(chatId: Long, confirmation: PendingConfirmation) {
        pending[chatId] = confirmation
    }

    fun get(chatId: Long): PendingConfirmation? = pending[chatId]

    fun remove(chatId: Long) {
        pending.remove(chatId)
    }
}

package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserPlanningScheduleChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Owns `users.telegram_chat_ready_at`: whether the bot can push to a user's Telegram chat. A linked
 * Telegram identity does not imply it — Telegram won't let a bot write to anyone who hasn't written
 * to it first — so every unprompted push (weekly planning, slot reminders) checks this instead.
 *
 * Both transitions re-publish [UserPlanningScheduleChangedEvent], so the weekly planning cron is
 * registered the moment the chat opens and dropped the moment it stops accepting messages.
 *
 * `REQUIRES_NEW`: callers include an `AFTER_COMMIT` listener (where joining the finished
 * transaction would silently lose the write) and a reminder dispatch whose own transaction must not
 * decide whether this sticks.
 */
@Service
class TelegramReachabilityService(
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(TelegramReachabilityService::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markReachable(userId: UUID) {
        if (userRepository.stampTelegramChatReady(userId, clock.instant()) == 0) return
        log.info("Telegram chat for user {} is now reachable", userId)
        eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markUnreachable(userId: UUID) {
        if (userRepository.clearTelegramChatReady(userId) == 0) return
        log.info("Telegram chat for user {} is no longer reachable; pausing pushes until they write to the bot", userId)
        eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
    }
}

package dev.itayp.tasker.service

import dev.itayp.nescioquid.telegram.TelegramAuthData
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Attaches additional login methods to an already-authenticated account ("connected accounts").
 * The [AuthIdentityEntity] model already supports many identities per user, so linking is mostly an
 * "attach an UNOWNED identity to the current user" operation.
 *
 * Conflict policy (decided): if the identity already belongs to a *different* account we refuse
 * rather than merge — merging two data-bearing accounts is a separate, riskier feature.
 */
@Service
class AccountLinkService(
    private val userRepository: UserRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val userCrypto: UserCryptoService,
    private val userSettingsService: UserSettingsService,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AccountLinkService::class.java)

    @Transactional(readOnly = true)
    fun listIdentities(userId: UUID): List<AuthIdentityEntity> =
        authIdentityRepository.findAllByUserId(userId)

    @Transactional
    fun linkTelegram(userId: UUID, data: TelegramAuthData): LinkResult {
        val key = data.telegramId.toString()
        val existing = authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, key)
        if (existing != null) {
            return if (existing.userId == userId) LinkResult.AlreadyLinked else LinkResult.ConflictOwnedByAnother
        }
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        // users.telegram_id is single-valued, so a user holds at most one Telegram account.
        if (user.telegramId != null) return LinkResult.AlreadyHasProvider

        val now = clock.instant()
        user.telegramId = data.telegramId
        user.telegramUsername = data.username
        user.telegramPhotoUrl = data.photoUrl
        user.telegramFirstName = userCrypto.encrypt(userId, data.firstName)
        // Linking a login method claims the account (one-way latch), exempting it from cleanup.
        user.claimed = true
        userRepository.save(user)
        // No-op unless the account was still on the DEMO tier — upgrades it to STANDARD if the cap
        // allows, otherwise leaves it on its existing DEMO budget.
        userSettingsService.upgradeToStandardOnClaim(userId)
        authIdentityRepository.save(AuthIdentityEntity().apply {
            id = UUID.randomUUID()
            this.userId = userId
            provider = AuthProvider.TELEGRAM
            providerUserId = key
            verifiedAt = now
            createdAt = now
            lastLoginAt = now
        })
        // A push channel now exists; re-register the planning cron that was skipped while channel-less.
        eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
        // First-time link only (not AlreadyLinked): greet the user on their new channel, after commit.
        eventPublisher.publishEvent(TelegramLinkedEvent(userId))
        log.info("Linked telegram identity to user {}", userId)
        return LinkResult.Success
    }

    @Transactional
    fun unlink(userId: UUID, provider: String): UnlinkResult {
        val identities = authIdentityRepository.findAllByUserId(userId)
        val target = identities.firstOrNull { it.provider == provider } ?: return UnlinkResult.NotLinked
        // The operator owns local logins. Removing the identity wouldn't stop the password working —
        // the next login would just provision a fresh, empty account under the same username.
        if (provider == AuthProvider.LOCAL) return UnlinkResult.NotUnlinkable
        // Never strand a user without a way back in.
        if (identities.size <= 1) return UnlinkResult.WouldRemoveLastMethod

        authIdentityRepository.delete(target)
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        when (provider) {
            AuthProvider.TELEGRAM -> {
                user.telegramId = null
                user.telegramUsername = null
                user.telegramFirstName = null
                user.telegramPhotoUrl = null
                user.telegramChatReadyAt = null
                userRepository.save(user)
                // Push channel gone; cancel/skip the planning cron.
                eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
            }
            AuthProvider.EMAIL -> {
                // Removing the email login also drops the address on file (and thus calendar invites).
                user.email = null
                user.emailHash = null
                user.emailVerifiedAt = null
                userRepository.save(user)
            }
        }
        log.info("Unlinked {} identity from user {}", provider, userId)
        return UnlinkResult.Success
    }
}

/** Published (after commit) when a user connects Telegram for the first time, to send a welcome. */
data class TelegramLinkedEvent(val userId: UUID)

sealed interface LinkResult {
    data object Success : LinkResult
    data object AlreadyLinked : LinkResult
    data object ConflictOwnedByAnother : LinkResult
    data object AlreadyHasProvider : LinkResult
}

sealed interface UnlinkResult {
    data object Success : UnlinkResult
    data object NotLinked : UnlinkResult
    data object WouldRemoveLastMethod : UnlinkResult
    data object NotUnlinkable : UnlinkResult
}

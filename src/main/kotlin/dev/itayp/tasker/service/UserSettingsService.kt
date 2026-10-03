package dev.itayp.tasker.service

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.access.AiTier
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.GenderOption
import dev.itayp.tasker.model.response.LanguageOption
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class UserPlanningScheduleChangedEvent(val userId: UUID)

/** Outcome of [UserSettingsService.appendToContextBlock]. */
enum class AppendContextResult {
    /** The addition was appended to the context block. */
    APPENDED,

    /** The addition would overflow the context-block cap; nothing was written. */
    FULL,

    /** The addition was blank; nothing was written. */
    NO_OP,
}

@Service
class UserSettingsService(
    private val settingsRepository: UserSettingsRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val userCrypto: UserCryptoService,
    private val aiProperties: AiProperties,
) {

    fun getOrCreate(userId: UUID): UserSettings = toDomain(userId, fetchOrCreate(userId))

    /**
     * Appends a single line to the user's (user-authored) context block, separated from any existing
     * content by a blank line. Backs the assistant's end-of-session "want me to remember this?" flow —
     * the user is always the one who accepts before we call this. Enforces the same
     * [CONTEXT_BLOCK_MAX_CHARS] soft cap as the settings form: if the addition wouldn't fit, nothing is
     * written and [AppendContextResult.FULL] is returned so the caller can tell the user to trim it in
     * Settings first. A blank addition is a no-op.
     */
    fun appendToContextBlock(userId: UUID, addition: String): AppendContextResult {
        val trimmed = addition.trim()
        if (trimmed.isEmpty()) return AppendContextResult.NO_OP
        val entity = fetchOrCreate(userId)
        val existing = userCrypto.decrypt(userId, entity.contextBlock)?.trimEnd().orEmpty()
        val combined = if (existing.isEmpty()) trimmed else "$existing\n\n$trimmed"
        if (combined.length > CONTEXT_BLOCK_MAX_CHARS) return AppendContextResult.FULL
        entity.contextBlock = userCrypto.encrypt(userId, combined)
        settingsRepository.save(entity)
        return AppendContextResult.APPENDED
    }

    fun update(userId: UUID, request: UpdateUserSettingsRequest): UserSettings {
        validateSettingsInput(
            timeZone = request.timeZone,
            preferredLanguage = request.preferredLanguage,
            gender = request.gender,
            planningCron = request.planningCron,
            weekStartDay = request.weekStartDay,
            dailyDigestCron = request.dailyDigestCron,
        )
        val entity = fetchOrCreate(userId)
        val scheduleChanged = entity.planningCron != request.planningCron ||
            entity.timeZone != request.timeZone
        entity.displayName = userCrypto.encrypt(userId, request.displayName)
        entity.contextBlock = userCrypto.encrypt(userId, request.contextBlock)
        entity.timeZone = request.timeZone
        entity.preferredLanguage = request.preferredLanguage
        entity.calendarInviteEmail = request.calendarInviteEmail
        entity.appReminders = request.appReminders
        entity.gender = request.gender
        entity.agentDescription = userCrypto.encrypt(userId, request.agentDescription)
        entity.planningCron = request.planningCron
        entity.weekStartDay = request.weekStartDay
        entity.autoArchiveDays = request.autoArchiveDays
        // A user can always opt out (false), but can only opt back in once their tier grants
        // access — otherwise a plain settings save would silently self-grant AI past the cap.
        entity.aiEnabled = request.aiEnabled && AiTier.fromName(entity.aiTier).grantsAccess
        entity.aiEnhancedReminders = request.aiEnhancedReminders
        entity.dailyDigestEnabled = request.dailyDigestEnabled
        entity.dailyDigestDueTasks = request.dailyDigestDueTasks
        entity.dailyDigestCron = request.dailyDigestCron
        val saved = settingsRepository.save(entity)
        if (scheduleChanged) {
            eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
        }
        return toDomain(userId, saved)
    }

    /**
     * `findAllByPlanningCronIsNotNull` returns raw entities for the scheduler; only the
     * non-sensitive scheduling fields (`planningCron`, `timeZone`, `weekStartDay`, `userId`)
     * are read. Sensitive fields stay as ciphertext on the returned entities — do not
     * surface them to callers without going through this service's domain mappers.
     */
    fun findAllWithPlanningCron(): List<UserSettingsEntity> =
        settingsRepository.findAllByPlanningCronIsNotNull()

    fun findAllWithAutoArchive(): List<UserSettingsEntity> =
        settingsRepository.findAllByAutoArchiveDaysIsNotNull()

    /**
     * Raw entities for the daily-digest scheduler, which reads only the non-sensitive scheduling
     * fields (`dailyDigestCron`, `dailyDigestLastRunAt`, `timeZone`, `userId`) — same caveat as
     * [findAllWithPlanningCron].
     */
    fun findAllWithDailyDigest(): List<UserSettingsEntity> =
        settingsRepository.findAllByDailyDigestEnabledTrue()

    /** Advances the daily-digest scheduler's watermark. */
    fun markDailyDigestRun(userId: UUID, at: Instant) {
        val entity = fetchOrCreate(userId)
        entity.dailyDigestLastRunAt = at
        settingsRepository.save(entity)
    }

    fun getLocale(userId: UUID): Locale = toLocale(fetchOrCreate(userId).preferredLanguage)

    /** The stored language tag only, without decrypting the rest of the settings row. Used by the
     * auth bootstrap (`/me`) so the SPA can pick its UI locale before first paint (docs/I18N.md, D3). */
    fun getPreferredLanguage(userId: UUID): String = fetchOrCreate(userId).preferredLanguage

    /** Maps a stored `preferredLanguage` tag to a [Locale]. Centralized so callers that already hold
     * a [UserSettings] don't re-fetch (and so the tag→locale rule lives in one place). */
    fun toLocale(preferredLanguage: String): Locale = Locale.forLanguageTag(preferredLanguage)

    /**
     * Creates the settings row for a brand-new user. [preferredLanguage], when non-null, is a
     * supported language code resolved from the registration request's `Accept-Language` (see
     * docs/I18N.md, D2); when null the entity's `en-US` default stands. Only ever called once per
     * user, at registration.
     *
     * [claimed] decides the initial AI grant: an unclaimed (demo) account is always granted
     * [AiTier.DEMO] outright — AI (including the web weekly-planning conversation, which needs no
     * Telegram) is part of the product's own funnel and must never hit the cap or show a "request
     * access" wall, but a demo signup never proves it's a real user, so it gets a bounded budget
     * rather than the full [AiTier.STANDARD] allowance. A claimed (real) account is granted
     * [AiTier.STANDARD] while the operator-configured cap ([AiProperties.tierCap]) has headroom;
     * past it, it still gets [AiTier.DEMO] rather than [AiTier.NONE] — the cap protects the *full*
     * budget, not AI access itself, and the DEMO ceiling already bounds the per-user cost the same
     * way it does for unclaimed accounts, so there is no runaway-bill risk in granting it
     * unconditionally. This count-then-insert isn't transactionally atomic against concurrent
     * registrations — at this app's beta scale a handful of near-simultaneous signups overshooting
     * the cap by one or two is an accepted risk, not engineered around.
     */
    fun initializeForNewUser(userId: UUID, preferredLanguage: String? = null, claimed: Boolean = true) {
        val grantedTier = if (claimed && standardTierCapHasHeadroom()) AiTier.STANDARD else AiTier.DEMO
        settingsRepository.save(UserSettingsEntity().apply {
            this.userId = userId
            preferredLanguage?.let { this.preferredLanguage = it }
            this.aiEnabled = true
            this.aiTier = grantedTier.tierName
        })
    }

    /**
     * Re-runs the [AiTier.STANDARD] grant decision for an account that just transitioned from
     * unclaimed (demo) to claimed — the same cap check [initializeForNewUser] applies to a
     * brand-new claimed registration, since claiming makes the account just as "real". Call from
     * every place that flips `UserEntity.claimed` to true (currently
     * `EmailVerificationService.confirmVerification` and `AccountLinkService.linkTelegram`).
     *
     * A no-op unless the account is currently on [AiTier.DEMO], so it's safe to call unconditionally
     * from a login/link path that also runs for an already-claimed account (claiming is a one-way
     * latch — re-claiming is a no-op there too). While the cap has headroom the account is upgraded
     * to [AiTier.STANDARD]; past it, the account simply **keeps** its existing DEMO budget rather
     * than being downgraded to [AiTier.NONE] — claiming an account must never take AI access away.
     *
     * Deliberately leaves `aiEnabled` untouched — unlike [initializeForNewUser], which sets it
     * alongside a fresh grant, this only ever runs against an existing row, and a demo user may have
     * explicitly toggled AI off in Settings before claiming. Silently flipping it back on would
     * override that choice; the (now-enabled) toggle in Settings is the user's own way back in.
     */
    fun upgradeToStandardOnClaim(userId: UUID) {
        val entity = fetchOrCreate(userId)
        if (AiTier.fromName(entity.aiTier) != AiTier.DEMO) return
        if (standardTierCapHasHeadroom()) {
            entity.aiTier = AiTier.STANDARD.tierName
            settingsRepository.save(entity)
        }
    }

    private fun standardTierCapHasHeadroom(): Boolean =
        settingsRepository.countByAiTierIn(GRANTED_AI_TIER_NAMES) < aiProperties.tierCap.maxGrantedUsers

    private fun fetchOrCreate(userId: UUID): UserSettingsEntity =
        settingsRepository.findById(userId).orElseGet {
            settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
        }

    /**
     * Shared enum/regex checks for the user-supplied subset of settings. Throws
     * [IllegalArgumentException] (which propagates as 400 / rolls back any
     * surrounding `@Transactional`) on any invalid value. Used by both the normal
     * settings-update endpoint and the account-import flow.
     */
    fun validateSettingsInput(
        timeZone: String,
        preferredLanguage: String,
        gender: String?,
        planningCron: String?,
        weekStartDay: String?,
        dailyDigestCron: String,
    ) {
        require(timeZone in SUPPORTED_TIME_ZONES) { "Unsupported time zone: $timeZone" }
        require(SUPPORTED_LANGUAGES.any { it.code == preferredLanguage }) {
            "Unsupported language: $preferredLanguage"
        }
        gender?.let {
            require(SUPPORTED_GENDERS.any { g -> g.code == it }) { "Unsupported gender: $it" }
        }
        planningCron?.let {
            require(CronExpression.isValidExpression(it)) { "Invalid cron expression: $it" }
        }
        weekStartDay?.let {
            require(runCatching { DayOfWeek.valueOf(it) }.isSuccess) { "Unsupported week start day: $it" }
        }
        // Stricter than planningCron: the digest must fire at most once a day, so a hand-written or
        // imported expression can't turn it into a spam loop. The settings UI only produces this shape.
        require(DAILY_DIGEST_CRON_SHAPE.matches(dailyDigestCron) && CronExpression.isValidExpression(dailyDigestCron)) {
            "Invalid daily digest schedule: $dailyDigestCron"
        }
    }

    private fun toDomain(userId: UUID, entity: UserSettingsEntity): UserSettings =
        UserSettings(
            userId = userId,
            displayName = userCrypto.decrypt(userId, entity.displayName),
            contextBlock = userCrypto.decrypt(userId, entity.contextBlock),
            timeZone = entity.timeZone,
            preferredLanguage = entity.preferredLanguage,
            calendarInviteEmail = entity.calendarInviteEmail,
            appReminders = entity.appReminders,
            gender = entity.gender,
            agentDescription = userCrypto.decrypt(userId, entity.agentDescription),
            planningCron = entity.planningCron,
            weekStartDay = entity.weekStartDay,
            autoArchiveDays = entity.autoArchiveDays,
            aiEnabled = entity.aiEnabled,
            aiEnhancedReminders = entity.aiEnhancedReminders,
            aiTier = entity.aiTier,
            dailyDigestEnabled = entity.dailyDigestEnabled,
            dailyDigestDueTasks = entity.dailyDigestDueTasks,
            dailyDigestCron = entity.dailyDigestCron,
        )

    companion object {
        /**
         * `0 <minute> <hour> * * <days>` — at most one firing a day. Days are `*` or a comma-separated
         * list of three-letter day names and ranges (`MON-FRI,SUN`).
         */
        private val DAILY_DIGEST_CRON_SHAPE =
            Regex("""0 \d{1,2} \d{1,2} \* \* (\*|[A-Z]{3}(-[A-Z]{3})?(,[A-Z]{3}(-[A-Z]{3})?)*)""", RegexOption.IGNORE_CASE)

        /** Soft cap on the user context block, mirroring the settings form's `@Size(max)` on `contextBlock`. */
        const val CONTEXT_BLOCK_MAX_CHARS = 4000

        /**
         * Tiers counted against [AiProperties.tierCap] — the cap on *claimed* accounts specifically,
         * so [AiTier.DEMO] is deliberately excluded even though it also grants access: unclaimed
         * accounts never reach this count (see [initializeForNewUser]), and a demo signup shouldn't
         * inflate what is meant to measure real registrations.
         */
        val GRANTED_AI_TIER_NAMES: List<String> = listOf(AiTier.STANDARD.tierName, AiTier.UNLIMITED.tierName)

        val SUPPORTED_TIME_ZONES: List<String> = (
            ZoneId.getAvailableZoneIds()
                .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") } +
            listOf("UTC")
        ).sorted()

        val SUPPORTED_GENDERS: List<GenderOption> = listOf(
            GenderOption("masculine", "Masculine"),
            GenderOption("feminine", "Feminine"),
            GenderOption("neutral", "Neutral"),
        )

        /**
         * Languages offered in the settings picker and accepted on update. Trimmed from the
         * original 14 to the set we actually support end-to-end (see docs/I18N.md, D1): every
         * added language now carries UI-translation + QA cost, not just channel bundle keys.
         *
         * The dormant `messages_de/es/fr/…​.properties` bundles are deliberately kept (not deleted):
         * validation only runs on *new* updates, so any stored preference still pointing at one keeps
         * working and falls back per-key to the base (English) bundle. Read paths must therefore
         * tolerate a stored code outside this list — never assume membership on read.
         */
        val SUPPORTED_LANGUAGES: List<LanguageOption> = listOf(
            LanguageOption("ar", "Arabic"),
            LanguageOption("en-GB", "English (UK)"),
            LanguageOption("en-US", "English (US)"),
            LanguageOption("he", "Hebrew"),
            LanguageOption("ru", "Russian"),
        )
    }
}

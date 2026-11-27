package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.capture.CaptureEntry
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.channel.AttachmentIntake
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.KeyedSerialExecutor
import dev.itayp.tasker.config.RateLimitProperties
import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * The provider-independent half of every messaging channel (`docs/MESSAGING-CHANNELS.md`,
 * section 1): a provider's webhook turns deliveries into [InboundEvent]s and calls [accept]; this
 * links chat accounts, gates on AI access, and drives `QuickAddFlow` exactly as Telegram's
 * unprompted capture does. Quick-add only: these channels never start a conversation, plan, or
 * run bot commands.
 *
 * [accept] returns at once, so the webhook can acknowledge before the model call. Events are
 * de-duplicated by provider message id, then run one at a time per conversation, in arrival
 * order, on virtual threads. Nothing is queued durably: a chat user sees an unanswered message and
 * resends it, and the provider redelivers whatever we failed to acknowledge.
 *
 * Never log message text, chat account ids or phone numbers: event ids and user ids only.
 */
@Service
class MessagingCaptureService(
    private val linkService: ChannelLinkService,
    private val registry: MessagingCaptureRegistry,
    private val quickAddFlow: QuickAddFlow,
    private val aiAccessService: AiAccessService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
    clock: Clock,
    rateLimitProperties: RateLimitProperties,
) {
    private val log = LoggerFactory.getLogger(MessagingCaptureService::class.java)
    private val deduplicator = InboundDeduplicator(clock)
    private val lanes = KeyedSerialExecutor<ConversationKey>()
    private val unlinkedReplyLimiter: RateLimiter = rateLimitProperties.channelUnlinkedReply.let {
        InMemoryRateLimiter(it.limit, it.windowSeconds * 1000)
    }

    /** Takes an event for processing and returns at once. A repeat of an accepted event is dropped. */
    fun accept(event: InboundEvent) {
        if (!deduplicator.firstSeen(event.channel, event.eventId)) {
            count(event, "duplicate")
            log.debug("Dropped redelivered {} event {}", event.channel.metricTag, event.eventId)
            return
        }
        lanes.execute(ConversationKey(event.channel, event.senderId)) { process(event) }
    }

    /** Handles one event on the calling thread. [accept] is the entry point; this is for tests. */
    internal fun process(event: InboundEvent) {
        val key = ConversationKey(event.channel, event.senderId)
        var userId: UUID? = null
        try {
            log.debug("Processing {} event {}", event.channel.metricTag, event.eventId)
            val code = (event.content as? InboundEvent.Content.Text)?.text?.let(ChannelLinkService::extractCode)
            if (code != null) {
                handleLinkCode(event, code)
                return
            }
            userId = linkService.resolve(event.channel, event.senderId)
            if (userId == null) {
                replyUnlinked(event)
                return
            }
            if (!aiAccessService.isAiAvailableForUser(userId)) {
                count(event, "ai_off")
                reply(event, "messaging.ai_disabled", locale(userId))
                return
            }
            capture(userId, key, event)
        } catch (e: Exception) {
            count(event, "failed")
            log.error("Failed to process {} event {} for user {}", event.channel.metricTag, event.eventId, userId, e)
            registry.remove(key)
            runCatching { reply(event, "command.handleUpdateFailed", userId?.let(::locale) ?: Locale.ENGLISH) }
                .onFailure { log.warn("Could not send the failure reply for {} event {}", event.channel.metricTag, event.eventId) }
        }
    }

    private fun capture(userId: UUID, key: ConversationKey, event: InboundEvent) {
        val inbound = when (val content = event.content) {
            is InboundEvent.Content.Text -> ChannelInbound.Text(content.text)
            is InboundEvent.Content.Selection -> ChannelInbound.Selection(content.optionId)
            is InboundEvent.Content.Unsupported -> {
                // Leaves any capture in progress alone: the user can still answer its card.
                count(event, "unsupported")
                reply(event, "quickadd.media.unsupported_type", locale(userId))
                return
            }
            is InboundEvent.Content.Media -> when (val fetched = content.fetch()) {
                is MediaFetch.Fetched -> fetched.media
                is MediaFetch.Rejected -> {
                    // Leaves any capture in progress alone, as above.
                    count(event, "media_rejected")
                    reply(event, rejectionKey(fetched.reason), locale(userId))
                    return
                }
            }
        }
        val state = registry.get(key)
        val entry = when {
            state != null -> quickAddFlow.handleInbound(userId, event.reply, state, inbound)
            inbound is ChannelInbound.Text -> quickAddFlow.beginUnprompted(userId, event.reply, inbound.text)
            inbound is ChannelInbound.Media ->
                quickAddFlow.beginUnpromptedFromMedia(userId, event.reply, inbound.attachments, inbound.caption)
            else -> {
                // A tap on a card whose capture has expired or finished.
                count(event, "expired_card")
                reply(event, "messaging.card_expired", locale(userId))
                return
            }
        }
        when (entry) {
            is CaptureEntry.Captured -> {
                count(event, "captured")
                registry.set(key, entry.state)
            }
            is CaptureEntry.Routed -> {
                // Planning, the weekly plan and stats live in the web app on these channels.
                count(event, "routed")
                registry.remove(key)
                reply(event, "messaging.capabilities", locale(userId))
            }
        }
    }

    private fun handleLinkCode(event: InboundEvent, code: String) {
        count(event, "link_code")
        when (val outcome = linkService.consume(event.channel, event.senderId, event.senderDisplay, code)) {
            is LinkOutcome.Linked -> reply(event, "messaging.linked", locale(outcome.userId))
            LinkOutcome.Invalid -> reply(event, "messaging.link.invalid", senderLocale(event))
            is LinkOutcome.Conflict -> reply(event, "messaging.link.conflict", locale(outcome.linkedUserId))
            LinkOutcome.RateLimited -> Unit
        }
    }

    private fun replyUnlinked(event: InboundEvent) {
        count(event, "unlinked")
        val limiterKey = "${event.channel.metricTag}:${event.senderId}"
        // English: there is no account to take a language from.
        if (unlinkedReplyLimiter.tryConsume(limiterKey)) reply(event, "messaging.unlinked", Locale.ENGLISH)
    }

    /** A linked sender's own language, else English. */
    private fun senderLocale(event: InboundEvent): Locale =
        linkService.resolve(event.channel, event.senderId)?.let(::locale) ?: Locale.ENGLISH

    private fun reply(event: InboundEvent, key: String, locale: Locale) {
        event.reply.send(ChannelMessage.Text(messageSource.getMessage(key, null, locale)))
    }

    private fun locale(userId: UUID): Locale = userSettingsService.getLocale(userId)

    private fun count(event: InboundEvent, outcome: String) =
        meterRegistry.counter(INBOUND_METRIC, "channel", event.channel.metricTag, "outcome", outcome).increment()

    private fun rejectionKey(reason: AttachmentIntake.Rejection): String = when (reason) {
        AttachmentIntake.Rejection.TOO_LARGE -> "quickadd.media.too_large"
        AttachmentIntake.Rejection.UNSUPPORTED_TYPE -> "quickadd.media.unsupported_type"
        // Not quickadd.media.download_failed, which names Telegram.
        AttachmentIntake.Rejection.DOWNLOAD_FAILED -> "messaging.media.download_failed"
    }

    @PreDestroy
    fun shutdown() = lanes.close()

    companion object {
        const val INBOUND_METRIC = "tasker.channel.inbound"
    }
}

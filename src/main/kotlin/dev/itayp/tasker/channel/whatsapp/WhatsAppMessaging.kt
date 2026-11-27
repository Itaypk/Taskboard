package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.messaging.ChannelLinkService
import dev.itayp.tasker.channel.messaging.InboundEvent
import dev.itayp.tasker.channel.messaging.LinkInstructions
import dev.itayp.tasker.channel.messaging.MessagingCaptureService
import dev.itayp.tasker.channel.messaging.MessagingProvider
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.net.URLEncoder
import java.util.Locale

/**
 * WhatsApp quick-add, present only when fully configured: offers WhatsApp for linking in Settings,
 * and turns verified webhook deliveries into [InboundEvent]s for [MessagingCaptureService].
 */
@Component
@ConditionalOnWhatsAppCapture
class WhatsAppMessaging(
    private val properties: WhatsAppProperties,
    private val client: WhatsAppClient,
    private val captureService: MessagingCaptureService,
    private val linkService: ChannelLinkService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
) : MessagingProvider {
    private val log = LoggerFactory.getLogger(WhatsAppMessaging::class.java)
    private val mediaFetcher = WhatsAppMediaFetcher(client)

    override val channel = ChannelType.WHATSAPP

    /** Opens the chat with our number, the code already typed. No number, no link: the code still works typed. */
    override fun linkInstructions(code: String): LinkInstructions =
        LinkInstructions(client.displayNumber()?.let { "https://wa.me/$it?text=${URLEncoder.encode(code, Charsets.UTF_8)}" })

    /** Hands each user message in a verified delivery to the capture service. Returns at once. */
    fun deliver(payload: JsonNode) {
        val messages = WhatsAppWebhookParser.parse(payload, properties.phoneNumberId) { ref ->
            InboundEvent.Content.Media { mediaFetcher.fetch(ref) }
        }
        messages.forEach { message ->
            log.debug("WhatsApp message {} ({}) accepted", message.messageId, message.content::class.simpleName)
            captureService.accept(
                InboundEvent(
                    channel = ChannelType.WHATSAPP,
                    eventId = message.messageId,
                    senderId = message.userId,
                    senderDisplay = message.phone?.let { "+$it" },
                    content = message.content,
                    reply = WhatsAppConversationChannel(
                        client = client,
                        recipient = WhatsAppRecipient(message.phone, message.userId),
                        inboundMessageId = message.messageId,
                        text = { key -> messageSource.getMessage(key, null, senderLocale(message.userId)) },
                        meterRegistry = meterRegistry,
                    ),
                ),
            )
        }
    }

    private fun senderLocale(userId: String): Locale =
        linkService.resolve(ChannelType.WHATSAPP, userId)?.let(userSettingsService::getLocale) ?: Locale.ENGLISH
}

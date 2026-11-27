package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.ChannelCapabilities
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.MessageFormatter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A reply channel to one WhatsApp sender (`docs/MESSAGING-CHANNELS.md`, section 5). Built per
 * inbound message, since the typing indicator is tied to that message's id.
 *
 * A [ChannelMessage.Choice] becomes reply buttons when it fits WhatsApp's (at most three, titles of
 * at most 20 characters), else a list message (at most ten rows), else a numbered text list the user
 * answers in words: `QuickAddFlow` takes free text in every state.
 *
 * @param text a localized bot string by message key, in the sender's language. Called only when a
 *   message needs one of ours (a list's button), so looking up the language costs nothing otherwise.
 */
class WhatsAppConversationChannel(
    private val client: WhatsAppClient,
    private val recipient: WhatsAppRecipient,
    private val inboundMessageId: String,
    private val text: (String) -> String,
    private val meterRegistry: MeterRegistry,
    /** How often "typing…" is re-sent while work is in flight. Overridden in tests. */
    private val typingRefresh: Duration = DEFAULT_TYPING_REFRESH,
) : ConversationChannel {

    private val log = LoggerFactory.getLogger(WhatsAppConversationChannel::class.java)

    override val type = ChannelType.WHATSAPP

    override val capabilities = ChannelCapabilities(
        supportsAutocompletions = false,
        supportsInlineButtons = true,
        supportsSlashCommands = false,
    )

    override val formatter: MessageFormatter = WhatsAppMessageFormatter

    /**
     * WhatsApp drops the indicator after 25 seconds, and a capture with a model call can take
     * longer, so it is re-sent until [block] finishes, capped like Telegram's.
     */
    override fun <T> whileWorking(block: () -> T): T {
        client.showTyping(inboundMessageId)
        val done = AtomicBoolean(false)
        val keepAlive = Thread.ofVirtual().start {
            var ticks = 0
            while (!done.get() && ticks++ < MAX_TYPING_TICKS) {
                try {
                    Thread.sleep(typingRefresh)
                } catch (_: InterruptedException) {
                    break
                }
                if (!done.get()) client.showTyping(inboundMessageId)
            }
        }
        return try {
            block()
        } finally {
            done.set(true)
            keepAlive.interrupt()
        }
    }

    override fun send(message: ChannelMessage) {
        try {
            when (message) {
                is ChannelMessage.Text -> sendText(message.text)
                is ChannelMessage.Choice -> sendChoice(message)
            }
            count("sent")
        } catch (e: WhatsAppApiException) {
            if (e.code !in CHAT_CLOSED_CODES) {
                count("failed")
                throw e
            }
            // The 24-hour window closed or the user blocked us: nothing to retry, and we never push.
            count("undeliverable")
            log.warn("WhatsApp reply to message {} not delivered: the chat is closed (code {})", inboundMessageId, e.code)
        }
    }

    private fun sendText(body: String) = splitText(body, TEXT_LIMIT).forEach { client.sendText(recipient, it) }

    private fun sendChoice(choice: ChannelMessage.Choice) {
        val options = choice.options
        val fitsButtons = options.size <= MAX_BUTTONS && options.all { it.label.length <= BUTTON_TITLE_LIMIT }
        val bodyLimit = if (fitsButtons) BUTTON_BODY_LIMIT else LIST_BODY_LIMIT
        if (!fitsButtons && options.size > MAX_LIST_ROWS) {
            val numbered = options.mapIndexed { i, option -> "${i + 1}. ${option.label}" }.joinToString("\n")
            sendText("${choice.prompt}\n\n$numbered")
            return
        }
        // A prompt too long for an interactive body goes first on its own, the options under a short line.
        val body = if (choice.prompt.length <= bodyLimit) {
            choice.prompt
        } else {
            sendText(choice.prompt)
            text(KEY_CHOOSE)
        }
        if (fitsButtons) {
            client.sendButtons(recipient, body, options.map { WhatsAppOption(it.id, it.label) })
        } else {
            client.sendList(
                recipient,
                body,
                cut(text(KEY_CHOOSE), LIST_BUTTON_LIMIT),
                options.map { option ->
                    val title = cut(option.label, ROW_TITLE_LIMIT)
                    // A cut title shows the full label underneath, where rows have room for it.
                    WhatsAppOption(option.id, title, option.label.takeIf { title != it }?.let { cut(it, ROW_DESCRIPTION_LIMIT) })
                },
            )
        }
    }

    private fun count(outcome: String) =
        meterRegistry.counter(OUTBOUND_METRIC, "channel", type.metricTag, "outcome", outcome).increment()

    companion object {
        const val OUTBOUND_METRIC = "tasker.channel.outbound"

        /** Asks for a pick under a list's button, and above options whose prompt was sent alone. */
        const val KEY_CHOOSE = "messaging.choose"

        const val TEXT_LIMIT = 4096
        const val MAX_BUTTONS = 3
        const val BUTTON_TITLE_LIMIT = 20
        const val BUTTON_BODY_LIMIT = 1024
        const val MAX_LIST_ROWS = 10
        const val LIST_BODY_LIMIT = 4096
        const val LIST_BUTTON_LIMIT = 20
        const val ROW_TITLE_LIMIT = 24
        const val ROW_DESCRIPTION_LIMIT = 72

        /**
         * Meta error codes meaning the message can't reach this chat at all: 131047, more than 24
         * hours since the user's last message; 131026, undeliverable (blocked, or no WhatsApp).
         */
        val CHAT_CLOSED_CODES = setOf(131047, 131026)

        private val DEFAULT_TYPING_REFRESH: Duration = Duration.ofSeconds(20)

        /** Belt and braces against a block that never returns: about three minutes of typing. */
        private const val MAX_TYPING_TICKS = 9

        /** Splits at paragraph breaks, then line breaks, then anywhere, so each part fits [limit]. */
        internal fun splitText(text: String, limit: Int): List<String> {
            if (text.length <= limit) return listOf(text)
            val parts = mutableListOf<String>()
            var rest = text
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                val cutAt = window.lastIndexOf("\n\n").takeIf { it > 0 }
                    ?: window.lastIndexOf('\n').takeIf { it > 0 }
                    ?: limit
                parts += rest.substring(0, cutAt).trimEnd()
                rest = rest.substring(cutAt).trimStart('\n')
            }
            if (rest.isNotEmpty()) parts += rest
            return parts
        }

        /** Cuts to [limit] UTF-16 units with an ellipsis, never through the middle of an emoji. */
        internal fun cut(text: String, limit: Int): String {
            if (text.length <= limit) return text
            var head = text.take(limit - 1)
            if (head.last().isHighSurrogate()) head = head.dropLast(1)
            return head.trimEnd() + "…"
        }
    }
}

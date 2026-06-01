package dev.itayp.tasker.channel

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Channel implementation used by the dev REST flow: outbound messages are buffered in-process
 * and drained by the controller on each round-trip. Capabilities are set to "everything" so
 * the planning logic exercises the rich-message paths without a real Telegram client.
 */
class InMemoryConversationChannel(
    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        supportsAutocompletions = true,
        supportsInlineButtons = true,
    ),
) : ConversationChannel {

    override val formatter: MessageFormatter = PlainTextMessageFormatter

    private val outbox: ConcurrentLinkedQueue<ChannelMessage> = ConcurrentLinkedQueue()
    private val debugOutbox: ConcurrentLinkedQueue<ToolCallEvent> = ConcurrentLinkedQueue()

    override fun send(message: ChannelMessage) {
        outbox.add(message)
    }

    override fun logToolCall(name: String, arguments: String) {
        debugOutbox.add(ToolCallEvent(name, arguments))
    }

    fun drain(): List<ChannelMessage> {
        val drained = mutableListOf<ChannelMessage>()
        while (true) {
            val msg = outbox.poll() ?: break
            drained.add(msg)
        }
        return drained
    }

    fun drainToolCallEvents(): List<ToolCallEvent> {
        val drained = mutableListOf<ToolCallEvent>()
        while (true) {
            val event = debugOutbox.poll() ?: break
            drained.add(event)
        }
        return drained
    }
}

data class ToolCallEvent(val name: String, val arguments: String)

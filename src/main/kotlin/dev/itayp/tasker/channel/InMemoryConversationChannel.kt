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

    private val outbox: ConcurrentLinkedQueue<ChannelMessage> = ConcurrentLinkedQueue()

    override fun send(message: ChannelMessage) {
        outbox.add(message)
    }

    fun drain(): List<ChannelMessage> {
        val drained = mutableListOf<ChannelMessage>()
        while (true) {
            val msg = outbox.poll() ?: break
            drained.add(msg)
        }
        return drained
    }
}

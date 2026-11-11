package dev.itayp.tasker.channel

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Channel implementation that buffers outbound messages in-process to be drained by the caller
 * on each round-trip. Used by the synchronous request/response planning flows that have no
 * push transport of their own: the dev REST console ([dev.itayp.tasker.controller.DevPlanningController])
 * and the production web channel ([dev.itayp.tasker.controller.WebPlanningController]).
 *
 * The orchestrator runs an entire turn synchronously and calls [send] inline, so a fresh channel
 * can be created per request and fully drained before the response is returned. Capabilities are
 * set to "everything" so the planning logic exercises the rich-message paths. The [formatter]
 * picks the markup the messages are rendered with — plain text for the dev console (which prints
 * via `textContent`), Markdown for the web channel (which renders via its MarkdownRenderer).
 */
class BufferedConversationChannel(
    override val type: ChannelType,
    override val formatter: MessageFormatter = PlainTextMessageFormatter,
    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        supportsAutocompletions = true,
        supportsInlineButtons = true,
    ),
) : ConversationChannel {

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

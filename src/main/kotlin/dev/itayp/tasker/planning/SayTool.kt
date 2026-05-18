package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolKind
import org.springframework.stereotype.Component

/**
 * The model speaks to the user by calling this tool. The orchestrator owns the channel
 * dispatch (parsing args, sending [dev.itayp.tasker.channel.ChannelMessage.Text]); the
 * tool itself is purely declarative — its `execute` returns a noop ack so the
 * `tool_result` message expected by the next model call is well-formed.
 */
@Component
class SayTool : AiTool {

    override val name: String = "say"

    override val description: String = "Send a message to the user. Use for any prose " +
        "you'd otherwise type as content. Multiple `say` calls in one turn are rendered " +
        "in order."

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "text" to mapOf(
                "type" to "string",
                "description" to "The message body shown to the user.",
            ),
            "suggested_replies" to mapOf(
                "type" to "array",
                "description" to "Optional short reply suggestions the channel can render " +
                    "as autocompletions. Ignored on channels that don't support them.",
                "items" to mapOf("type" to "string"),
            ),
        ),
        "required" to listOf("text"),
    )

    override val kind: ToolKind = ToolKind.ONE_WAY_OUTPUT

    override fun execute(arguments: String): String = """{"ok":true}"""
}

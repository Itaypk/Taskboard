package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.itayp.nescioquid.openrouter.jsonSchema
import dev.itayp.nescioquid.openrouter.tool.AiTool
import dev.itayp.nescioquid.openrouter.tool.ToolKind
import org.springframework.stereotype.Component

/**
 * The model asks the user a multiple-choice question. The orchestrator queues each
 * call, dispatches them serially through the channel as the user answers, and fills
 * in the `tool_result` for each tool_call_id once a reply is bound to it. See
 * `docs/PLANNING-FLOW.md` for the suspension/escape-hatch semantics.
 *
 * Like [SayTool], this tool is declarative — its `execute` is never called by the
 * orchestrator; the noop ack is here only to satisfy the [AiTool] contract.
 */
@Component
class AskChoiceTool : AiTool {

    override val name: String = "ask_choice"

    override val description: String = "Ask the user a multiple-choice question. Each " +
        "option must have an id (machine-readable) and a label (shown to the user). " +
        "Always include an escape option with id 'discuss' so the user can opt out " +
        "of the queue and discuss in free text."

    override val parameters: Map<String, Any> = jsonSchema<AskChoiceArgs>(strict = false)

    override val kind: ToolKind = ToolKind.INTERACTIVE_INPUT

    override fun execute(arguments: String): String =
        error("ask_choice is dispatched by the orchestrator and never executed directly")

    private data class AskChoiceArgs(
        @JsonPropertyDescription("The question shown to the user.")
        val prompt: String,
        @JsonPropertyDescription("The choices the user can pick from.")
        val options: List<ChoiceOption>,
    )

    private data class ChoiceOption(
        @JsonPropertyDescription("Stable identifier for this option, e.g. 'slot_a' or 'discuss'.")
        val id: String,
        @JsonPropertyDescription("Short label shown to the user.")
        val label: String,
    )
}

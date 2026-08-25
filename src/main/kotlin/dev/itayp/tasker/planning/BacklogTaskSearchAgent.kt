package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.parseAssistantJsonResponseOrNull
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.service.BacklogTaskService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Sub-agent backing the `find_task` tool. The weekly planner only ever sees a bounded candidate
 * slate, so when the user mentions a task that may already exist we run a single, isolated LLM call
 * over the user's full (non-archived) task list to locate it — keeping the whole list out of the
 * main conversation. No embeddings/RAG; the scale doesn't warrant it.
 */
@Service
class BacklogTaskSearchAgent(
    private val aiClient: ReasoningAwareAiClient,
    private val backlogTaskService: BacklogTaskService,
    private val promptTemplateLoader: PromptTemplateLoader,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${tasker.ai.task-assistant-model}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(BacklogTaskSearchAgent::class.java)

    fun search(userId: UUID, query: String): List<TaskMatch> {
        val tasks = backlogTaskService.getTasksAcrossBoards(userId, null)
        if (tasks.isEmpty()) return emptyList()

        val systemPrompt = promptTemplateLoader.load("task-search/system.md").render(emptyMap())
        val userMessage = promptTemplateLoader.load("task-search/user.md").render(mapOf(
            "query" to query,
            "task_list" to renderTaskList(tasks),
        ))
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage),
            ),
            temperature = 0.0,
            maxTokens = 512,
        )

        val context = AiCallContext(userId = userId.toString(), conversationType = AiConversationType.TASK_SEARCH)
        val raw = aiClient.chat(request, context).choices.firstOrNull()?.message?.contentText.orEmpty()
        val parsed = parseAssistantJsonResponseOrNull(
            objectMapper, raw, SearchResult::class.java,
            AiConversationType.TASK_SEARCH, meterRegistry, log, "find_task",
        )?.matches.orEmpty()
        val matches = resolveAgainstBacklog(parsed, tasks)
        log.debug(
            "find_task searched {} tasks, sub-agent proposed {}, resolved {} matches",
            tasks.size, parsed.size, matches.size,
        )
        return matches
    }

    /**
     * Re-derives every match from the live task it names, rather than trusting the sub-agent's echo.
     * Two things fall out of that: an id the model invented (or copied wrong) is dropped instead of
     * reaching the planner as an unschedulable `task_id`, and each surviving match carries the task's
     * real `status` and `relevant_from`. The planner needs those — the corpus spans all non-archived
     * tasks, so a match may be already done or not yet relevant, and the caller has to be able to see
     * that before scheduling it.
     */
    private fun resolveAgainstBacklog(matches: List<TaskMatch>, tasks: List<BacklogTask>): List<TaskMatch> {
        val byId = tasks.associateBy { it.id.toString() }
        return matches.mapNotNull { match ->
            val task = byId[match.taskId] ?: run {
                log.warn("find_task sub-agent returned an id that isn't in the backlog; dropping it")
                return@mapNotNull null
            }
            match.copy(
                title = task.title,
                status = task.status.name.lowercase(),
                relevantFrom = task.relevantFrom?.toString(),
            )
        }
    }

    private fun renderTaskList(tasks: List<BacklogTask>): String = tasks.joinToString("\n") { task ->
        buildString {
            append("- [").append(task.id).append("] ").append(task.title)
            append(" · category=").append(task.category.label)
            append(" · status=").append(task.status.name.lowercase())
            task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
            task.deadline?.let { append(" · deadline=").append(it) }
            task.relevantFrom?.let { append(" · relevant_from=").append(it) }
            if (task.tags.isNotEmpty()) append(" · tags=").append(task.tags.joinToString(",") { it.label })
        }
    }
}

// Unset fields are omitted so the tool_result the planner reads stays compact.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TaskMatch(
    @JsonProperty("task_id") val taskId: String,
    val title: String,
    val confidence: String? = null,
    /** `todo` / `done` — filled in from the live task, not from the sub-agent. */
    val status: String? = null,
    /** ISO date the task becomes relevant, if set. Filled in from the live task. */
    @JsonProperty("relevant_from") val relevantFrom: String? = null,
)

private data class SearchResult(
    val matches: List<TaskMatch> = emptyList(),
)

package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.client.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.parseAssistantJsonResponse
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.service.BacklogTaskService
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
    private val aiClient: AiClient,
    private val backlogTaskService: BacklogTaskService,
    private val promptTemplateLoader: PromptTemplateLoader,
    private val objectMapper: ObjectMapper,
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

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.TASK_SEARCH)
        val raw = aiClient.chat(request, context).choices.firstOrNull()?.message?.contentText.orEmpty()
        val matches = runCatching { parseAssistantJsonResponse(objectMapper, raw, SearchResult::class.java) }
            .getOrElse {
                log.warn("find_task could not parse sub-agent output: {}", it.message)
                null
            }?.matches.orEmpty()
        log.debug("find_task searched {} tasks, returned {} matches", tasks.size, matches.size)
        return matches
    }

    private fun renderTaskList(tasks: List<BacklogTask>): String = tasks.joinToString("\n") { task ->
        buildString {
            append("- [").append(task.id).append("] ").append(task.title)
            append(" · category=").append(task.category.label)
            task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
            task.deadline?.let { append(" · deadline=").append(it) }
            if (task.tags.isNotEmpty()) append(" · tags=").append(task.tags.joinToString(",") { it.label })
        }
    }
}

data class TaskMatch(
    @JsonProperty("task_id") val taskId: String,
    val title: String,
    val confidence: String? = null,
)

private data class SearchResult(
    val matches: List<TaskMatch> = emptyList(),
)

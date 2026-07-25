package dev.itayp.tasker.notification

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * One-off AI nudge for a due slot reminder (Phase 2b). Mirrors [dev.itayp.tasker.planning.TaskSuggestionAgent]:
 * a single, stateless [AiClient.chat] producing a short, warm reminder in the user's language — no
 * stored conversation (the free-form "let's discuss" follow-up is Phase 2c).
 *
 * Returns null on any failure (the AI gate refusing, tier limit, an empty/blank reply) so the
 * dispatcher can fall back to the static template — AI is an enhancement, never a delivery dependency.
 * The model output carries the (decrypted) task title, so it is never logged.
 */
@Service
class ReminderMessageAgent(
    private val aiClient: ReasoningAwareAiClient,
    private val userSettingsService: UserSettingsService,
    private val promptTemplateLoader: PromptTemplateLoader,
    @Value("\${tasker.ai.task-assistant-model}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(ReminderMessageAgent::class.java)

    /** The generated nudge, or null to fall back to the static template. */
    fun generate(
        userId: UUID,
        title: String,
        description: String?,
        slotStartIso: String,
        locale: Locale,
        zone: ZoneId,
    ): String? = runCatching {
        val settings = userSettingsService.getOrCreate(userId)
        val languageName = runCatching {
            userSettingsService.toLocale(settings.preferredLanguage).getDisplayLanguage(Locale.ENGLISH)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "English"
        val contextBlock = settings.contextBlock?.takeIf { it.isNotBlank() } ?: "(no personal context shared)"
        val startTime = OffsetDateTime.parse(slotStartIso)
            .atZoneSameInstant(zone)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

        val systemPrompt = promptTemplateLoader.load("slot-reminder/system.md")
            .render(mapOf("language" to languageName))
        val userMessage = promptTemplateLoader.load("slot-reminder/user.md").render(mapOf(
            "title" to title,
            "description_block" to (description?.takeIf { it.isNotBlank() }
                ?.let { "Notes: ${it.take(DESCRIPTION_LIMIT)}\n" } ?: ""),
            "start_time" to startTime,
            "user_context" to contextBlock,
        ))
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage),
            ),
            temperature = 0.7,
            // On reasoning models OpenRouter counts reasoning tokens against max_tokens; a budget
            // this small was fully consumed by reasoning, leaving an empty message that silently
            // fell back to the static template. Keep headroom for the short reminder text itself.
            maxTokens = 512,
        )

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.SLOT_REMINDER)
        val reply = aiClient.chat(request, context).choices.firstOrNull()?.message?.contentText?.trim()
        reply?.takeIf { it.isNotBlank() }
    }.getOrElse {
        log.warn("AI reminder generation failed for user {}; falling back to static template", userId, it)
        null
    }

    private companion object {
        const val DESCRIPTION_LIMIT = 500
    }
}

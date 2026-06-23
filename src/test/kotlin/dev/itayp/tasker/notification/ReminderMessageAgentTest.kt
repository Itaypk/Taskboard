package dev.itayp.tasker.notification

import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatResponse
import dev.itayp.tasker.ai.client.Choice
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderMessageAgentTest {

    private val aiClient: AiClient = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val agent = ReminderMessageAgent(aiClient, userSettingsService, PromptTemplateLoader(), "test-model")

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun stubSettings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = "Alex", contextBlock = "Works mornings.",
                timeZone = "UTC", preferredLanguage = "en", calendarInviteEmail = false,
                gender = null, agentDescription = null, planningCron = null, weekStartDay = null,
                autoArchiveDays = null,
            ),
        )
    }

    private fun generate() = agent.generate(
        userId, "Buy milk", "1% only", "2026-05-13T10:00:00Z", Locale.ENGLISH, ZoneId.of("UTC"),
    )

    @Test
    fun `returns the trimmed model reply`() {
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("  Milk run in 10 — you've got this!  "))

        assertEquals("Milk run in 10 — you've got this!", generate())
    }

    @Test
    fun `returns null when the model reply is blank`() {
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("   "))

        assertNull(generate())
    }

    @Test
    fun `returns null when the AI call fails so the dispatcher can fall back`() {
        whenever(aiClient.chat(any(), any())).thenThrow(RuntimeException("ai down"))

        assertNull(generate())
    }

    private fun chatResponse(content: String) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = content), finishReason = "stop")),
        usage = null,
    )
}

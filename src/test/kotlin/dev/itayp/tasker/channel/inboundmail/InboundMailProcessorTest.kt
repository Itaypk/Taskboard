package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.access.AiUsageLimitExceededException
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.SavedCapture
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailEntity
import dev.itayp.tasker.jpa.InboundMailStatus
import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.repository.InboundMailRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboundMailProcessorTest {

    @Mock lateinit var repository: InboundMailRepository
    @Mock lateinit var userCryptoService: UserCryptoService
    @Mock lateinit var suggestionAgent: TaskSuggestionAgent
    @Mock lateinit var quickAddFlow: QuickAddFlow
    @Mock lateinit var aiAccessService: AiAccessService
    @Mock lateinit var confirmation: InboundMailConfirmation

    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val userId = UUID.randomUUID()
    private val objectMapper = jacksonObjectMapper()
    private val payload = InboundMailPayload(
        subject = "Call the plumber", fromAddress = "me@example.org", fromName = null,
        body = "The sink leaks.", attachmentCount = 0, verified = true,
    )
    private val saved = SavedCapture(UUID.randomUUID(), listOf("Call the plumber"), listOf(UUID.randomUUID()), false)

    private val processor by lazy {
        InboundMailProcessor(
            repository, userCryptoService, objectMapper, suggestionAgent, quickAddFlow, aiAccessService,
            confirmation, SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @BeforeEach
    fun setUp() {
        whenever(userCryptoService.decrypt(eq(userId), any())).thenReturn(objectMapper.writeValueAsString(payload))
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)
    }

    private fun mail(attempts: Int = 0) = InboundMailEntity().apply {
        id = UUID.randomUUID()
        this.userId = this@InboundMailProcessorTest.userId
        dedupKey = "k"
        this.attempts = attempts
        nextAttemptAt = now
        this.payload = byteArrayOf(1)
        receivedAt = now
    }

    private fun drafted() = SuggestionOutcome.Draft(listOf(CapturedItem.Task(TaskDraft(title = "Call the plumber"))))

    @Test
    fun `saves the model's draft, clears the payload and confirms`() {
        whenever(suggestionAgent.quickAddDraftFromEmail(eq(userId), any())).thenReturn(drafted())
        whenever(quickAddFlow.saveWithoutConfirmation(userId, drafted().items)).thenReturn(saved)
        val mail = mail()

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.DONE)
        assertThat(mail.payload).isNull()
        assertThat(mail.processedAt).isEqualTo(now)
        verify(confirmation).sendSaved(userId, payload, saved)
        val prompt = argumentCaptor<String>().apply { verify(suggestionAgent).quickAddDraftFromEmail(eq(userId), capture()) }.firstValue
        assertThat(prompt).contains("Subject: Call the plumber").contains("From: <me@example.org>").contains("The sink leaks.")
    }

    @Test
    fun `saves the email verbatim when AI is not available`() {
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)
        whenever(quickAddFlow.saveWithoutConfirmation(eq(userId), any())).thenReturn(saved)

        processor.process(mail())

        verify(suggestionAgent, never()).quickAddDraftFromEmail(any(), any())
        val items = argumentCaptor<List<CapturedItem>>().apply { verify(quickAddFlow).saveWithoutConfirmation(eq(userId), capture()) }.firstValue
        val draft = (items.single() as CapturedItem.Task).draft
        assertThat(draft.title).isEqualTo("Call the plumber")
        assertThat(draft.description).isEqualTo("From: me@example.org\n\nThe sink leaks.")
    }

    @Test
    fun `falls back to verbatim when the AI budget is spent`() {
        whenever(suggestionAgent.quickAddDraftFromEmail(eq(userId), any())).thenThrow(AiUsageLimitExceededException(userId, 10L, 10L))
        whenever(quickAddFlow.saveWithoutConfirmation(eq(userId), any())).thenReturn(saved)
        val mail = mail()

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.DONE)
    }

    @Test
    fun `retries a model error later, keeping the payload`() {
        whenever(suggestionAgent.quickAddDraftFromEmail(eq(userId), any())).thenThrow(IllegalStateException("upstream 502"))
        val mail = mail()

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.PENDING)
        assertThat(mail.attempts).isEqualTo(1)
        assertThat(mail.nextAttemptAt).isAfter(now)
        assertThat(mail.payload).isNotNull()
        verify(quickAddFlow, never()).saveWithoutConfirmation(any(), any())
    }

    @Test
    fun `on the last attempt a model error falls back to verbatim instead of losing the email`() {
        whenever(suggestionAgent.quickAddDraftFromEmail(eq(userId), any())).thenThrow(IllegalStateException("upstream 502"))
        whenever(quickAddFlow.saveWithoutConfirmation(eq(userId), any())).thenReturn(saved)
        val mail = mail(attempts = InboundMailProcessor.MAX_ATTEMPTS - 1)

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.DONE)
        verify(confirmation).sendSaved(userId, payload, saved)
    }

    @Test
    fun `gives up after the last attempt when even saving fails, and says so`() {
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)
        whenever(quickAddFlow.saveWithoutConfirmation(eq(userId), any())).thenThrow(IllegalStateException("db down"))
        val mail = mail(attempts = InboundMailProcessor.MAX_ATTEMPTS - 1)

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.FAILED)
        assertThat(mail.payload).isNull()
        verify(confirmation).sendFailure(userId, payload)
    }

    @Test
    fun `fails an unreadable payload once instead of retrying it forever`() {
        whenever(userCryptoService.decrypt(eq(userId), any())).thenReturn("not json")
        val mail = mail()

        processor.process(mail)

        assertThat(mail.status).isEqualTo(InboundMailStatus.FAILED)
        verify(quickAddFlow, never()).saveWithoutConfirmation(any(), any())
    }
}

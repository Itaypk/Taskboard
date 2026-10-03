package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals

class DailyDigestServiceTest {

    private val composer: DailyDigestComposer = mock()
    private val repository: DailyDigestRepository = mock {
        on { save(any<DailyDigestEntity>()) } doAnswer { (it.arguments[0] as DailyDigestEntity).apply { id = digestId } }
    }
    private val userSettingsService: UserSettingsService = mock()
    private val channelResolver: ScheduledConversationChannelResolver = mock()
    private val aiAccessService: AiAccessService = mock()
    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val meterRegistry = SimpleMeterRegistry()
    private val service = DailyDigestService(
        composer, repository, userSettingsService, channelResolver, aiAccessService, messageSource, meterRegistry,
    )

    private val userId = UUID.randomUUID()
    private val digestId = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = Instant.parse("2026-10-05T05:02:00Z") // Monday 08:02 local
    private val today = LocalDate.parse("2026-10-05")
    private val channel: ConversationChannel = mock {
        on { formatter } doAnswer { HtmlMessageFormatter }
    }

    private fun givenUser(dueTasks: Boolean = true, language: String = "en-US", channelAvailable: Boolean = true) {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = null, contextBlock = null, timeZone = zone.id,
                preferredLanguage = language, calendarInviteEmail = false, gender = null,
                agentDescription = null, planningCron = null, weekStartDay = null, autoArchiveDays = null,
                dailyDigestDueTasks = dueTasks,
            ),
        )
        whenever(userSettingsService.toLocale(any())).thenAnswer { Locale.forLanguageTag(it.arguments[0] as String) }
        whenever(channelResolver.resolve(userId)).thenReturn(
            if (channelAvailable) ScheduledConversationChannelResolver.Resolved(channel) {} else null,
        )
    }

    private fun sentChoice(): ChannelMessage.Choice {
        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        return captor.firstValue as ChannelMessage.Choice
    }

    private val fullContent = DailyDigestContent(
        today = listOf(DailyDigestContent.PlannedBlock("Write <report>", LocalTime.of(9, 30))),
        due = listOf(
            DailyDigestContent.DueTask(UUID.randomUUID(), "Pay bills", today),
            DailyDigestContent.DueTask(UUID.randomUUID(), "Renew passport", LocalDate.parse("2026-09-28")),
        ),
        dueOverflow = 2,
    )

    @Test
    fun `renders both sections, records the listed due tasks and offers every button`() {
        givenUser()
        whenever(composer.compose(userId, today, zone, includeDue = true)).thenReturn(fullContent)
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)

        assertEquals(DailyDigestService.Outcome.SENT, service.send(userId, now))

        val choice = sentChoice()
        assertEquals(
            """
            <b>☀️ Your day: Mon, Oct 5</b>

            <b>Planned for today</b>
            • 9:30 AM · Write &lt;report&gt;

            <b>Due, not in your plan</b>
            • Pay bills (due today)
            • Renew passport (was due Sep 28)
            …and 2 more
            """.trimIndent(),
            // Normalize the narrow no-break space newer CLDR data puts before AM/PM.
            choice.prompt.replace(' ', ' '),
        )
        assertEquals(
            listOf("ack", "plan", "mutw", "muty").map { "dig:$it:$digestId" },
            choice.options.map { it.id },
        )
        val saved = argumentCaptor<DailyDigestEntity>()
        verify(repository).save(saved.capture())
        assertEquals(today, saved.firstValue.digestDate)
        assertEquals(fullContent.due.map { it.taskId to it.deadline }, saved.firstValue.dueTasks.map { it.backlogTaskId to it.deadline })
        assertEquals(1.0, counter("success"))
    }

    @Test
    fun `without due tasks or AI only the acknowledgement is offered`() {
        givenUser()
        whenever(composer.compose(userId, today, zone, includeDue = true))
            .thenReturn(fullContent.copy(due = emptyList(), dueOverflow = 0))
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)

        service.send(userId, now)

        assertEquals(listOf("dig:ack:$digestId"), sentChoice().options.map { it.id })
    }

    @Test
    fun `the due section follows the user's setting`() {
        givenUser(dueTasks = false)
        whenever(composer.compose(userId, today, zone, includeDue = false)).thenReturn(fullContent.copy(due = emptyList()))

        service.send(userId, now)

        verify(composer).compose(userId, today, zone, includeDue = false)
    }

    @Test
    fun `nothing to say means nothing is sent or recorded`() {
        givenUser()
        whenever(composer.compose(userId, today, zone, includeDue = true))
            .thenReturn(DailyDigestContent(emptyList(), emptyList(), 0))

        assertEquals(DailyDigestService.Outcome.EMPTY, service.send(userId, now))
        verify(channel, never()).send(any())
        verify(repository, never()).save(any<DailyDigestEntity>())
        assertEquals(1.0, counter("skipped", content = "none"))
    }

    @Test
    fun `no deliverable channel skips before composing`() {
        givenUser(channelAvailable = false)

        assertEquals(DailyDigestService.Outcome.NO_CHANNEL, service.send(userId, now))
        verify(composer, never()).compose(any(), any(), any(), any())
    }

    @Test
    fun `a digest already sent today is not sent again`() {
        givenUser()
        whenever(repository.existsByUserIdAndDigestDate(userId, today)).thenReturn(true)

        assertEquals(DailyDigestService.Outcome.ALREADY_SENT, service.send(userId, now))
        verify(channel, never()).send(any())
    }

    @Test
    fun `a failed send is counted and rethrown so the digest row rolls back`() {
        givenUser()
        whenever(composer.compose(userId, today, zone, includeDue = true)).thenReturn(fullContent)
        whenever(channel.send(any())).thenThrow(RuntimeException("telegram down"))

        assertThrows<RuntimeException> { service.send(userId, now) }
        assertEquals(1.0, counter("failure"))
    }

    @Test
    fun `renders in the user's language`() {
        givenUser(language = "he")
        whenever(composer.compose(eq(userId), eq(today), eq(zone), any()))
            .thenReturn(fullContent.copy(today = emptyList()))

        service.send(userId, now)

        val prompt = sentChoice().prompt
        assert(prompt.contains("הגיע המועד, לא בתוכנית")) { prompt }
    }

    private fun counter(outcome: String, content: String = "static") = meterRegistry.counter(
        "tasker.notification.sent",
        "type", "daily_digest", "channel", "telegram", "outcome", outcome, "content", content,
    ).count()
}

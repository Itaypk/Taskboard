package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.service.UserSettingsService
import dev.itayp.tasker.channel.ChannelType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doReturn
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

class DailyDigestActionHandlerTest {

    private val repository: DailyDigestRepository = mock()
    private val muteService: DeadlineReminderMuteService = mock()
    private val planningSessionService: PlanningSessionService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val meterRegistry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-10-05T05:30:00Z"), ZoneOffset.UTC)
    private val handler = DailyDigestActionHandler(
        repository, muteService, planningSessionService, userSettingsService, messageSource, meterRegistry, clock,
    )

    private val userId = UUID.randomUUID()
    private val digestId = UUID.randomUUID()
    private val channel: ConversationChannel = mock { on { type } doReturn ChannelType.TELEGRAM }
    private val listed = mutableListOf(DigestDueTask(UUID.randomUUID(), LocalDate.parse("2026-10-01")))

    private fun givenDigest(owner: UUID = userId) {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.US)
        whenever(repository.findById(digestId)).thenReturn(Optional.of(DailyDigestEntity().apply {
            id = digestId
            this.userId = owner
            digestDate = LocalDate.parse("2026-10-05")
            sentAt = clock.instant()
            dueTasks = listed
        }))
    }

    private fun sentText(): String {
        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        return (captor.firstValue as ChannelMessage.Text).text
    }

    @Test
    fun `callbacks without the digest prefix are not ours`() {
        assertEquals(DailyDigestActionHandler.Result.NotOurs, handler.handle(userId, channel, "rem:ack:${UUID.randomUUID()}"))
        verifyNoInteractions(repository, channel)
    }

    @Test
    fun `mute until next week mutes the listed tasks until the next plan week starts`() {
        givenDigest()
        whenever(planningSessionService.currentWeekStart(userId)).thenReturn(LocalDate.parse("2026-10-04"))

        val result = handler.handle(userId, channel, DailyDigestAction.MUTE_DUE_UNTIL_NEXT_WEEK.callbackData(digestId))

        assertEquals(DailyDigestActionHandler.Result.Handled, result)
        verify(muteService).mute(userId, listed, LocalDate.parse("2026-10-11"))
        assertEquals("🔕 Okay, no reminders about these due tasks until Oct 11, 2026.", sentText())
    }

    @Test
    fun `mute for good mutes the listed tasks for a year`() {
        givenDigest()
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = null, contextBlock = null, timeZone = "Asia/Jerusalem",
                preferredLanguage = "en-US", calendarInviteEmail = false, gender = null,
                agentDescription = null, planningCron = null, weekStartDay = null, autoArchiveDays = null,
            ),
        )

        handler.handle(userId, channel, DailyDigestAction.MUTE_DUE_FOR_GOOD.callbackData(digestId))

        verify(muteService).mute(userId, listed, LocalDate.parse("2027-10-05"))
    }

    @Test
    fun `revisit the plan hands back to the channel's plan flow without replying`() {
        givenDigest()

        val result = handler.handle(userId, channel, DailyDigestAction.REVISIT_PLAN.callbackData(digestId))

        assertEquals(DailyDigestActionHandler.Result.StartPlanning, result)
        verify(channel, never()).send(any())
    }

    @Test
    fun `a digest owned by someone else is treated as expired and mutes nothing`() {
        givenDigest(owner = UUID.randomUUID())

        val result = handler.handle(userId, channel, DailyDigestAction.MUTE_DUE_FOR_GOOD.callbackData(digestId))

        assertEquals(DailyDigestActionHandler.Result.Handled, result)
        verify(muteService, never()).mute(any(), any(), any())
        assertEquals("This digest is no longer available.", sentText())
    }

    @Test
    fun `callback data round-trips and rejects malformed payloads`() {
        assertEquals(
            DailyDigestAction.Parsed(DailyDigestAction.ACK, digestId),
            DailyDigestAction.parse(DailyDigestAction.ACK.callbackData(digestId)),
        )
        assertEquals(null, DailyDigestAction.parse("dig:nope:$digestId"))
        assertEquals(null, DailyDigestAction.parse("dig:ack:not-a-uuid"))
        assert(DailyDigestAction.entries.all { it.callbackData(digestId).toByteArray().size <= 64 })
    }
}

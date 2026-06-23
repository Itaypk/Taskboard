package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ReminderDeliveryResolverTest {

    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var channelResolver: ScheduledConversationChannelResolver

    private val resolver by lazy { ReminderDeliveryResolver(userSettingsService, channelResolver) }
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `returns null when appReminders is off`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(appReminders = false))

        assertNull(resolver.resolve(userId))
    }

    @Test
    fun `returns null when there is no deliverable channel`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(appReminders = true))
        whenever(channelResolver.resolve(userId)).thenReturn(null)

        assertNull(resolver.resolve(userId))
    }

    @Test
    fun `returns a context with the user's locale and zone when eligible`() {
        val channel = mock<ConversationChannel>()
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            settings(appReminders = true, preferredLanguage = "fr-FR", timeZone = "America/New_York"),
        )
        whenever(channelResolver.resolve(userId))
            .thenReturn(ScheduledConversationChannelResolver.Resolved(channel) {})

        val ctx = resolver.resolve(userId)

        requireNotNull(ctx)
        assertEquals(channel, ctx.channel)
        assertEquals(Locale.forLanguageTag("fr-FR"), ctx.locale)
        assertEquals(ZoneId.of("America/New_York"), ctx.zone)
        assertTrue(ctx.aiEnhanced)
    }

    @Test
    fun `aiEnhanced is false when AI is disabled or enhanced reminders are opted out`() {
        val channel = mock<ConversationChannel>()
        whenever(channelResolver.resolve(userId))
            .thenReturn(ScheduledConversationChannelResolver.Resolved(channel) {})

        whenever(userSettingsService.getOrCreate(userId))
            .thenReturn(settings(appReminders = true, aiEnabled = false, aiEnhancedReminders = true))
        assertEquals(false, resolver.resolve(userId)?.aiEnhanced)

        whenever(userSettingsService.getOrCreate(userId))
            .thenReturn(settings(appReminders = true, aiEnabled = true, aiEnhancedReminders = false))
        assertEquals(false, resolver.resolve(userId)?.aiEnhanced)
    }

    private fun settings(
        appReminders: Boolean,
        preferredLanguage: String = "en-US",
        timeZone: String = "UTC",
        aiEnabled: Boolean = true,
        aiEnhancedReminders: Boolean = true,
    ) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = timeZone,
        preferredLanguage = preferredLanguage,
        calendarInviteEmail = false,
        appReminders = appReminders,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
        aiEnabled = aiEnabled,
        aiEnhancedReminders = aiEnhancedReminders,
    )
}

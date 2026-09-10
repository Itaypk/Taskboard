package dev.itayp.tasker.notification

import dev.itayp.tasker.planning.InviteDeliveryResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.Locale
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class NotificationChannelSummaryTest {

    @Mock lateinit var inviteDeliveryResolver: InviteDeliveryResolver
    @Mock lateinit var reminderDeliveryResolver: ReminderDeliveryResolver

    private val summary by lazy { NotificationChannelSummary(inviteDeliveryResolver, reminderDeliveryResolver) }
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `names both channels when email and Telegram are both eligible`() {
        whenever(inviteDeliveryResolver.resolveEmailContext(userId))
            .thenReturn(InviteDeliveryResolver.EmailContext("alice@example.com", Locale.ENGLISH))
        whenever(reminderDeliveryResolver.resolve(userId)).thenReturn(reminderContext())

        val description = summary.describe(userId)

        assertTrue(description.contains("alice@example.com"), description)
        assertTrue(description.contains("Telegram"), description)
        assertFalse(description.contains("NOT get a Telegram"), description)
    }

    @Test
    fun `names email only and says Telegram will NOT fire`() {
        whenever(inviteDeliveryResolver.resolveEmailContext(userId))
            .thenReturn(InviteDeliveryResolver.EmailContext("alice@example.com", Locale.ENGLISH))
        whenever(reminderDeliveryResolver.resolve(userId)).thenReturn(null)

        val description = summary.describe(userId)

        assertTrue(description.contains("alice@example.com"), description)
        assertTrue(description.contains("NOT get a Telegram"), description)
    }

    @Test
    fun `names Telegram only and says no calendar invitation will be sent`() {
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(null)
        whenever(reminderDeliveryResolver.resolve(userId)).thenReturn(reminderContext())

        val description = summary.describe(userId)

        assertTrue(description.contains("Telegram reminder"), description)
        assertTrue(description.contains("NOT receive a calendar invitation"), description)
    }

    @Test
    fun `warns of no delivery method when neither channel is eligible`() {
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(null)
        whenever(reminderDeliveryResolver.resolve(userId)).thenReturn(null)

        val description = summary.describe(userId)

        assertTrue(description.contains("NO active reminder"), description)
        assertTrue(description.contains("NO calendar"), description)
        assertTrue(description.contains("NO Telegram"), description)
    }

    private fun reminderContext() = ReminderDeliveryResolver.ReminderContext(
        channel = org.mockito.kotlin.mock(),
        locale = Locale.ENGLISH,
        zone = java.time.ZoneId.of("UTC"),
        aiEnhanced = false,
    )
}

package dev.itayp.tasker.oneoff

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.OneOffEventEntity
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.repository.OneOffEventRepository
import dev.itayp.tasker.service.BoardAccessDeniedException
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class OneOffEventServiceTest {

    @Mock private lateinit var repository: OneOffEventRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var inviteDeliveryResolver: InviteDeliveryResolver
    @Mock private lateinit var inviteDispatcher: OneOffEventInviteDispatcher

    private val boardCrypto = noopBoardCryptoService()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC)
    private val emailProperties = EmailProperties(
        enabled = true,
        scheduling = EmailProperties.SenderConfig(from = "scheduling@backlog.fyi", fromName = "Backlog.fyi"),
    )

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    private val writer: OneOffEventWriter by lazy {
        OneOffEventWriter(repository, boardMembershipService, boardCrypto, clock)
    }

    private val service: OneOffEventService by lazy {
        OneOffEventService(
            writer = writer,
            repository = repository,
            boardCrypto = boardCrypto,
            userSettingsService = userSettingsService,
            inviteDeliveryResolver = inviteDeliveryResolver,
            inviteDispatcher = inviteDispatcher,
            emailProperties = emailProperties,
        )
    }

    @Test
    fun `createEvents persists, assigns a stable ical_uid, and dispatches one invite per event`() {
        val start1 = Instant.parse("2026-07-15T16:30:00Z")
        val end1 = Instant.parse("2026-07-15T17:30:00Z")
        val start2 = Instant.parse("2026-07-16T09:00:00Z")
        val end2 = Instant.parse("2026-07-16T10:00:00Z")
        val drafts = listOf(
            OneOffEventDraft("Parent-teacher conference", start1, end1, location = "School auditorium"),
            OneOffEventDraft("Dentist", start2, end2, notes = "Annual cleaning"),
        )
        // Repository.saveAll: stamp ids on the entities so the domain mapping can read them back.
        whenever(repository.saveAll(any<List<OneOffEventEntity>>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val items = invocation.arguments[0] as List<OneOffEventEntity>
            items.onEach { if (it.id == null) it.id = UUID.randomUUID() }
        }
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(
            InviteDeliveryResolver.EmailContext(email = "user@example.com", locale = Locale.ENGLISH)
        )
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(userSettings("Asia/Jerusalem"))

        val created = service.createEvents(userId, boardId, drafts)

        verify(boardMembershipService).requireMember(userId, boardId)
        assertEquals(2, created.events.size)
        assertTrue(created.invitesScheduled)
        assertEquals("Parent-teacher conference", created.events[0].title)
        assertEquals("School auditorium", created.events[0].location)
        assertEquals(start1, created.events[0].startsAt)
        assertTrue(created.events[0].icalUid.isNotBlank())
        assertTrue(created.events[1].icalUid.isNotBlank())
        assertTrue(created.events[0].icalUid != created.events[1].icalUid)

        val invitesCaptor = argumentCaptor<List<OneOffEventInviteDispatcher.Invite>>()
        verify(inviteDispatcher).dispatch(invitesCaptor.capture())
        val invites = invitesCaptor.firstValue
        assertEquals(2, invites.size)
        assertEquals("user@example.com", invites[0].userEmail)
        assertEquals("scheduling@backlog.fyi", invites[0].organizerEmail)
        assertEquals("Backlog.fyi", invites[0].organizerName)
        assertEquals(created.events[0].icalUid, invites[0].event.icalUid)
    }

    @Test
    fun `createEvents skips invite dispatch when the user has no eligible email channel`() {
        whenever(repository.saveAll(any<List<OneOffEventEntity>>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val items = invocation.arguments[0] as List<OneOffEventEntity>
            items.onEach { if (it.id == null) it.id = UUID.randomUUID() }
        }
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(null)

        val created = service.createEvents(
            userId, boardId,
            listOf(
                OneOffEventDraft(
                    "Flight",
                    Instant.parse("2026-08-01T05:00:00Z"),
                    Instant.parse("2026-08-01T09:00:00Z"),
                )
            ),
        )

        assertEquals(1, created.events.size)
        assertEquals(false, created.invitesScheduled)
        verify(repository).saveAll(any<List<OneOffEventEntity>>())
        verify(inviteDispatcher, never()).dispatch(any())
    }

    @Test
    fun `createEvents propagates the board-access denial from the membership guard`() {
        whenever(boardMembershipService.requireMember(userId, boardId))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        assertFailsWith<BoardAccessDeniedException> {
            service.createEvents(
                userId, boardId,
                listOf(
                    OneOffEventDraft(
                        "Stranger's board",
                        Instant.parse("2026-08-01T05:00:00Z"),
                        Instant.parse("2026-08-01T06:00:00Z"),
                    )
                ),
            )
        }
        verify(repository, never()).saveAll(any<List<OneOffEventEntity>>())
        verify(inviteDispatcher, never()).dispatch(any())
    }

    @Test
    fun `createEvents rejects an event whose end is before its start`() {
        assertFailsWith<IllegalArgumentException> {
            service.createEvents(
                userId, boardId,
                listOf(
                    OneOffEventDraft(
                        "Negative duration",
                        Instant.parse("2026-08-01T10:00:00Z"),
                        Instant.parse("2026-08-01T09:00:00Z"),
                    )
                ),
            )
        }
        verify(repository, never()).saveAll(any<List<OneOffEventEntity>>())
    }

    @Test
    fun `createEvents short-circuits on an empty draft list`() {
        val created = service.createEvents(userId, boardId, emptyList())
        assertEquals(0, created.events.size)
        assertEquals(false, created.invitesScheduled)
        verify(boardMembershipService, never()).requireMember(any(), any())
        verify(repository, never()).saveAll(any<List<OneOffEventEntity>>())
        verify(inviteDispatcher, never()).dispatch(any())
    }

    private fun userSettings(timeZone: String) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = timeZone,
        preferredLanguage = "en",
        calendarInviteEmail = true,
        appReminders = true,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )
}

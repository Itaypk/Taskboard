package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class AccountServiceTest {

    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository
    @Mock private lateinit var tagRepository: BacklogTaskTagRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var settingsRepository: UserSettingsRepository
    @Mock private lateinit var jdbcTemplate: JdbcTemplate

    @InjectMocks private lateinit var service: AccountService

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val categoryId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val tagId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    @Test
    fun `exportAccount emits formatVersion 1 and all v1 fields`() {
        val workCategory = BacklogTaskCategoryEntity().apply {
            id = categoryId
            this.userId = this@AccountServiceTest.userId
            label = "Work"
            swatchId = CategoryColor.SUNSHINE
        }
        val urgentTag = BacklogTaskTagEntity().apply {
            id = tagId
            this.userId = this@AccountServiceTest.userId
            label = "urgent"
            colorId = TagColor.CORAL
            description = "needs attention"
        }
        val task = BacklogTaskEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@AccountServiceTest.userId
            title = "Buy bread"
            description = "From the place on 5th"
            url = "https://example.com"
            priority = TaskPriority.HIGH
            deadline = LocalDate.parse("2026-06-01")
            estimatedMinutes = 15
            status = TaskStatus.TODO
            category = workCategory
            tags = mutableSetOf(urgentTag)
            sortKey = "a3f"
            createdAt = Instant.parse("2026-05-01T12:00:00Z")
            updatedAt = Instant.parse("2026-05-10T12:00:00Z")
            relevantFrom = LocalDate.parse("2026-05-20")
        }
        val user = UserEntity().apply {
            id = this@AccountServiceTest.userId
            telegramUsername = "alice"
            telegramFirstName = "Alice"
            email = "alice@example.com"
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        val settings = UserSettingsEntity().apply {
            this.userId = this@AccountServiceTest.userId
            displayName = "Alice"
            contextBlock = "I prefer deep work in the morning"
            timeZone = "Europe/London"
            preferredLanguage = "en-US"
            calendarInviteEmail = true
            gender = "feminine"
            agentDescription = "Founder working on X"
            planningCron = "0 30 9 * * MON"
            weekStartDay = "MONDAY"
            autoArchiveDays = 30
        }

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings))
        whenever(categoryRepository.findAllByUserId(userId)).thenReturn(listOf(workCategory))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(listOf(urgentTag))
        whenever(taskRepository.findAllByUserIdOrderBySortKeyAsc(userId)).thenReturn(listOf(task))

        val result = service.exportAccount(userId)

        assertEquals(1, result.formatVersion)

        assertEquals("alice@example.com", result.user.email)
        assertEquals("Alice", result.user.telegramFirstName)

        val exportedSettings = assertNotNull(result.settings)
        assertEquals(true, exportedSettings.calendarInviteEmail)
        assertEquals("feminine", exportedSettings.gender)
        assertEquals("Founder working on X", exportedSettings.agentDescription)
        assertEquals("0 30 9 * * MON", exportedSettings.planningCron)
        assertEquals("MONDAY", exportedSettings.weekStartDay)
        assertEquals(30, exportedSettings.autoArchiveDays)

        assertEquals(1, result.tasks.size)
        assertEquals("2026-05-20", result.tasks[0].relevantFrom)
        assertEquals("Work", result.categories[0].label)
        assertEquals("sunshine", result.categories[0].swatchId)
        assertEquals("coral", result.tags[0].colorId)
    }

    @Test
    fun `exportAccount handles null settings and null relevantFrom`() {
        val task = BacklogTaskEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@AccountServiceTest.userId
            title = "No date task"
            status = TaskStatus.TODO
            sortKey = "b00"
            createdAt = Instant.parse("2026-05-01T12:00:00Z")
            relevantFrom = null
        }
        val user = UserEntity().apply {
            id = this@AccountServiceTest.userId
            email = null
        }

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.empty())
        whenever(categoryRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(taskRepository.findAllByUserIdOrderBySortKeyAsc(userId)).thenReturn(listOf(task))

        val result = service.exportAccount(userId)

        assertEquals(1, result.formatVersion)
        assertNull(result.settings)
        assertNull(result.user.email)
        assertNull(result.tasks[0].relevantFrom)
    }
}

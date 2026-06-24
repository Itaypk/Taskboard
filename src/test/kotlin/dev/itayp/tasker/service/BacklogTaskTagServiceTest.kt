package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.request.UpdateTagRequest
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(MockitoExtension::class)
class BacklogTaskTagServiceTest {

    @Mock private lateinit var tagRepository: BacklogTaskTagRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var taskChangeService: BacklogTaskChangeService

    @InjectMocks private lateinit var service: BacklogTaskTagService

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    private fun tag(id: UUID, label: String, color: TagColor = TagColor.VIOLET) =
        BacklogTaskTagEntity().apply {
            this.id = id
            this.boardId = this@BacklogTaskTagServiceTest.boardId
            this.label = label
            this.colorId = color
        }

    @Test
    fun `getAllForUser returns tags mapped to domain`() {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(listOf(tag(UUID.randomUUID(), "deep-work")))

        val result = service.getAllForUser(userId)

        assertEquals(1, result.size)
        assertEquals("deep-work", result[0].label)
        assertEquals(TagColor.VIOLET, result[0].colorId)
    }

    @Test
    fun `getAllForUser returns empty list when no tags exist`() {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())

        assertEquals(0, service.getAllForUser(userId).size)
    }

    @Test
    fun `listTagsWithUsage orders by usage desc then label, defaulting unused tags to zero`() {
        val popular = UUID.randomUUID()
        val rare = UUID.randomUUID()
        val unused = UUID.randomUUID()
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(listOf(
            tag(rare, "beta"), tag(popular, "alpha"), tag(unused, "zeta"),
        ))
        whenever(taskRepository.countTasksPerTag(boardId)).thenReturn(listOf(
            arrayOf<Any>(popular, 5L), arrayOf<Any>(rare, 1L),
        ))

        val result = service.listTagsWithUsage(userId, boardId)

        assertEquals(listOf("alpha", "beta", "zeta"), result.map { it.tag.label })
        assertEquals(listOf(5, 1, 0), result.map { it.usageCount })
    }

    @Test
    fun `updateTag renames and recolors and bumps both watermarks`() {
        val id = UUID.randomUUID()
        val entity = tag(id, "old", TagColor.SAGE)
        whenever(tagRepository.findByIdAndBoardId(id, boardId)).thenReturn(entity)
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { it.arguments[0] }
        whenever(taskRepository.countTasksPerTag(boardId)).thenReturn(emptyList())

        val result = service.updateTag(userId, boardId, id, UpdateTagRequest("  New  ", "coral"))

        assertEquals("New", result.tag.label)
        assertEquals(TagColor.CORAL, result.tag.colorId)
        verify(taskChangeService).bumpTags(boardId)
        verify(taskChangeService).bumpWatermark(boardId)
    }

    @Test
    fun `updateTag throws when the tag is missing`() {
        val id = UUID.randomUUID()
        whenever(tagRepository.findByIdAndBoardId(id, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.updateTag(userId, boardId, id, UpdateTagRequest("x", "sage"))
        }
    }

    @Test
    fun `deleteTag detaches the tag from tasks then deletes it`() {
        val id = UUID.randomUUID()
        val entity = tag(id, "drop")
        val task = BacklogTaskEntity().apply { tags = mutableSetOf(entity) }
        whenever(tagRepository.findByIdAndBoardId(id, boardId)).thenReturn(entity)
        whenever(taskRepository.findAllByTagId(id)).thenReturn(listOf(task))

        service.deleteTag(userId, boardId, id)

        assertEquals(0, task.tags.size)
        verify(taskRepository).saveAll(listOf(task))
        verify(tagRepository).delete(entity)
        verify(taskChangeService).bumpTags(boardId)
        verify(taskChangeService).bumpWatermark(boardId)
    }

    @Test
    fun `deleteTag skips the tasks watermark when no task used the tag`() {
        val id = UUID.randomUUID()
        val entity = tag(id, "orphan")
        whenever(tagRepository.findByIdAndBoardId(id, boardId)).thenReturn(entity)
        whenever(taskRepository.findAllByTagId(id)).thenReturn(emptyList())

        service.deleteTag(userId, boardId, id)

        verify(tagRepository).delete(entity)
        verify(taskChangeService).bumpTags(boardId)
        verify(taskChangeService, never()).bumpWatermark(boardId)
    }
}

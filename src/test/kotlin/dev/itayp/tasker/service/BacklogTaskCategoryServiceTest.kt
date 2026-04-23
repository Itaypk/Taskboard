package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.request.CreateCategoryRequest
import dev.itayp.tasker.model.request.UpdateCategoryRequest
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(MockitoExtension::class)
class BacklogTaskCategoryServiceTest {

    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository

    @InjectMocks private lateinit var service: BacklogTaskCategoryService

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `getAllForUser returns mapped categories`() {
        whenever(categoryRepository.findAllByUserId(userId))
            .thenReturn(listOf(categoryEntity(label = "Work", swatchId = CategoryColor.SUNSHINE)))

        val result = service.getAllForUser(userId)

        assertEquals(1, result.size)
        assertEquals("Work", result[0].label)
        assertEquals(CategoryColor.SUNSHINE, result[0].swatchId)
    }

    @Test
    fun `createCategory converts swatchId to uppercase enum`() {
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskCategoryEntity).also { it.id = UUID.randomUUID() }
        }

        service.createCategory(userId, CreateCategoryRequest(label = "Home", swatchId = "mint"))

        val captor = argumentCaptor<BacklogTaskCategoryEntity>()
        verify(categoryRepository).save(captor.capture())
        assertEquals("Home", captor.firstValue.label)
        assertEquals(CategoryColor.MINT, captor.firstValue.swatchId)
        assertEquals(userId, captor.firstValue.userId)
    }

    @Test
    fun `updateCategory updates label and swatchId`() {
        val id = UUID.randomUUID()
        val entity = categoryEntity(id = id, label = "Old", swatchId = CategoryColor.SUNSHINE)
        whenever(categoryRepository.findByIdAndUserId(id, userId)).thenReturn(entity)
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { inv -> inv.arguments[0] as BacklogTaskCategoryEntity }

        service.updateCategory(userId, id, UpdateCategoryRequest(label = "New", swatchId = "blossom"))

        assertEquals("New", entity.label)
        assertEquals(CategoryColor.BLOSSOM, entity.swatchId)
    }

    @Test
    fun `updateCategory throws when category not found`() {
        val id = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndUserId(id, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.updateCategory(userId, id, UpdateCategoryRequest(label = "X", swatchId = "mint"))
        }
    }

    @Test
    fun `deleteCategory deletes when no tasks reference it`() {
        val id = UUID.randomUUID()
        whenever(taskRepository.existsByCategoryIdAndUserId(id, userId)).thenReturn(false)
        whenever(categoryRepository.findByIdAndUserId(id, userId)).thenReturn(categoryEntity(id = id))

        service.deleteCategory(userId, id)

        verify(categoryRepository).deleteById(id)
    }

    @Test
    fun `deleteCategory throws when tasks reference the category`() {
        val id = UUID.randomUUID()
        whenever(taskRepository.existsByCategoryIdAndUserId(id, userId)).thenReturn(true)

        assertFailsWith<IllegalStateException> {
            service.deleteCategory(userId, id)
        }
    }

    @Test
    fun `deleteCategory throws when category not found`() {
        val id = UUID.randomUUID()
        whenever(taskRepository.existsByCategoryIdAndUserId(id, userId)).thenReturn(false)
        whenever(categoryRepository.findByIdAndUserId(id, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.deleteCategory(userId, id)
        }
    }

    private fun categoryEntity(
        id: UUID = UUID.randomUUID(),
        label: String = "Work",
        swatchId: CategoryColor = CategoryColor.SUNSHINE,
    ) = BacklogTaskCategoryEntity().apply {
        this.id = id
        this.userId = this@BacklogTaskCategoryServiceTest.userId
        this.label = label
        this.swatchId = swatchId
    }
}

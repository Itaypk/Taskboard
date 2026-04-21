package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class BacklogTaskTagServiceTest {

    @Mock private lateinit var tagRepository: BacklogTaskTagRepository

    @InjectMocks private lateinit var service: BacklogTaskTagService

    @Test
    fun `getAllForUser returns tags mapped to domain`() {
        val entity = BacklogTaskTagEntity().apply {
            id = UUID.randomUUID()
            userId = "test"
            label = "deep-work"
            colorId = TagColor.VIOLET
        }
        whenever(tagRepository.findAllByUserId("test")).thenReturn(listOf(entity))

        val result = service.getAllForUser("test")

        assertEquals(1, result.size)
        assertEquals("deep-work", result[0].label)
        assertEquals(TagColor.VIOLET, result[0].colorId)
    }

    @Test
    fun `getAllForUser returns empty list when no tags exist`() {
        whenever(tagRepository.findAllByUserId("test")).thenReturn(emptyList())

        val result = service.getAllForUser("test")

        assertEquals(0, result.size)
    }
}

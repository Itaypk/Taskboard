package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskCategoryService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(BacklogTaskCategoryController::class)
@Import(SecurityConfiguration::class)
class BacklogTaskCategoryControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var categoryService: BacklogTaskCategoryService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000010")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun aCategory(id: UUID = categoryId, label: String = "Work") =
        BacklogTaskCategory(id, userId, label, CategoryColor.SUNSHINE)

    @Test
    fun `GET categories returns 200 with category list`() {
        whenever(categoryService.getAllForUser(userId)).thenReturn(listOf(aCategory()))

        mockMvc.perform(get("/api/v1/categories").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("Work"))
            .andExpect(jsonPath("$[0].swatchId").value("sunshine"))
    }

    @Test
    fun `POST categories returns 201 with created category`() {
        whenever(categoryService.createCategory(eq(userId), any())).thenReturn(aCategory(label = "Home"))

        mockMvc.perform(
            post("/api/v1/categories")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"Home","swatchId":"mint"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.label").value("Home"))
            .andExpect(jsonPath("$.id").value(categoryId.toString()))
    }

    @Test
    fun `PUT categories-id returns 200 with updated category`() {
        whenever(categoryService.updateCategory(eq(userId), eq(categoryId), any()))
            .thenReturn(aCategory(label = "Renamed"))

        mockMvc.perform(
            put("/api/v1/categories/$categoryId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"Renamed","swatchId":"sunshine"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.label").value("Renamed"))
    }

    @Test
    fun `PUT categories-id returns 404 when not found`() {
        whenever(categoryService.updateCategory(any(), any(), any()))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(
            put("/api/v1/categories/$categoryId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"X","swatchId":"mint"}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE categories-id returns 204`() {
        mockMvc.perform(delete("/api/v1/categories/$categoryId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE categories-id returns 404 when not found`() {
        doThrow(NoSuchElementException("not found")).whenever(categoryService).deleteCategory(any(), any())

        mockMvc.perform(delete("/api/v1/categories/$categoryId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE categories-id returns 409 when category is in use`() {
        doThrow(IllegalStateException("Category is in use")).whenever(categoryService).deleteCategory(any(), any())

        mockMvc.perform(delete("/api/v1/categories/$categoryId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isConflict)
    }
}

package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.CreateCategoryRequest
import dev.itayp.tasker.model.request.UpdateCategoryRequest
import dev.itayp.tasker.model.response.CategoryResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.service.BacklogTaskCategoryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/categories")
class BacklogTaskCategoryController(private val categoryService: BacklogTaskCategoryService) {

    @GetMapping
    fun getCategories(): ResponseEntity<List<CategoryResponse>> =
        ResponseEntity.ok(categoryService.getAllForUser("test").map { it.toResponse() })

    @PostMapping
    fun createCategory(@RequestBody request: CreateCategoryRequest): ResponseEntity<CategoryResponse> {
        val category = categoryService.createCategory("test", request)
        return ResponseEntity.status(HttpStatus.CREATED).body(category.toResponse())
    }

    @PutMapping("/{id}")
    fun updateCategory(
        @PathVariable id: UUID,
        @RequestBody request: UpdateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        return try {
            ResponseEntity.ok(categoryService.updateCategory("test", id, request).toResponse())
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteCategory(@PathVariable id: UUID): ResponseEntity<Void> {
        return try {
            categoryService.deleteCategory("test", id)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }
}

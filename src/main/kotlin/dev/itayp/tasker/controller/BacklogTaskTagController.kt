package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.UpdateTagRequest
import dev.itayp.tasker.model.response.TagResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskTagService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/boards/{boardId}/tags")
class BacklogTaskTagController(private val tagService: BacklogTaskTagService) {

    @GetMapping
    fun getTags(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<List<TagResponse>> =
        ResponseEntity.ok(tagService.listTagsWithUsage(principal.userId, boardId).map { it.toResponse() })

    @PutMapping("/{id}")
    fun updateTag(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateTagRequest,
    ): ResponseEntity<TagResponse> {
        return try {
            ResponseEntity.ok(tagService.updateTag(principal.userId, boardId, id, request).toResponse())
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteTag(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        return try {
            tagService.deleteTag(principal.userId, boardId, id)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
}

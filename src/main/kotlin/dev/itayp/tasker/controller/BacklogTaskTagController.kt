package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.TagResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskTagService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tags")
class BacklogTaskTagController(private val tagService: BacklogTaskTagService) {

    @GetMapping
    fun getTags(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<TagResponse>> =
        ResponseEntity.ok(tagService.getAllForUser(principal.userId).map { it.toResponse() })
}

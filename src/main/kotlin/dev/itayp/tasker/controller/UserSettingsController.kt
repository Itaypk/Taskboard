package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.UserSettingsResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/settings")
class UserSettingsController(private val userSettingsService: UserSettingsService) {

    @GetMapping
    fun getSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<UserSettingsResponse> =
        ResponseEntity.ok(userSettingsService.getOrCreate(principal.userId).toResponse())

    @PutMapping
    fun updateSettings(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: UpdateUserSettingsRequest,
    ): ResponseEntity<UserSettingsResponse> =
        ResponseEntity.ok(userSettingsService.update(principal.userId, request).toResponse())
}

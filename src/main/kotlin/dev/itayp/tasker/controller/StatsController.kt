package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.StatsResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.StatsService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/stats")
class StatsController(private val statsService: StatsService) {

    @GetMapping
    fun getStats(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<StatsResponse> =
        ResponseEntity.ok(statsService.computeStats(principal.userId).toResponse())
}

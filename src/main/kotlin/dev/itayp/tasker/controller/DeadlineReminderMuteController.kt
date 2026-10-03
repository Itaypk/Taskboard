package dev.itayp.tasker.controller

import dev.itayp.tasker.notification.digest.DeadlineReminderMuteService
import dev.itayp.tasker.security.TaskerPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Settings → "turn due-task reminders back on": clears every mute the user set from the daily
 * digest's due-task buttons (docs/DAILY-DIGEST.md).
 */
@RestController
@RequestMapping("/api/v1/settings/deadline-mutes")
class DeadlineReminderMuteController(
    private val muteService: DeadlineReminderMuteService,
) {
    data class ClearedMutesResponse(val cleared: Long)

    @DeleteMapping
    fun clearAll(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<ClearedMutesResponse> =
        ResponseEntity.ok(ClearedMutesResponse(muteService.clearAll(principal.userId)))
}

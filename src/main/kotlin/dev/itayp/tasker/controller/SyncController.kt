package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.SyncResponse
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.planning.PlanWatermarkService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardMembershipService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * The single poll an open board tab makes to stay fresh. Replaces the old trio (tasks has-changes +
 * tags + current plan): it returns one watermark per entity type so the client refetches only what
 * moved, plus the running [appVersion] so it can prompt a refresh after a redeploy.
 *
 * Board-scoped (task/tag/category watermarks are per-board); the plan watermark is resolved for the
 * authenticated user, since plan state is user-scoped.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/sync")
class SyncController(
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val planWatermarkService: PlanWatermarkService,
    private val boardMembershipService: BoardMembershipService,
    private val clock: Clock,
    buildProperties: ObjectProvider<BuildProperties>,
) {

    // Resolved once: the git commit baked in by `buildInfo()`, falling back to build time, then "dev"
    // (dev/test runs without a generated build-info.properties).
    private val appVersion: String = buildProperties.getIfAvailable()
        ?.let { it.get("commit") ?: it.time?.toString() }
        ?: "dev"

    @GetMapping
    fun sync(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<SyncResponse> {
        boardMembershipService.requireMember(principal.userId, boardId)
        val watermark = backlogTaskChangeService.readWatermark(boardId)
        return ResponseEntity.ok(
            SyncResponse(
                checkedAt = clock.instant().toString(),
                tasksChangedAt = watermark?.tasksChangedAt?.toString(),
                tagsChangedAt = watermark?.tagsChangedAt?.toString(),
                categoriesChangedAt = watermark?.categoriesChangedAt?.toString(),
                planChangedAt = planWatermarkService.read(principal.userId)?.toString(),
                appVersion = appVersion,
            )
        )
    }
}

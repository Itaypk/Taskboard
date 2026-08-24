package dev.itayp.tasker.external

import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.security.ApiTokenAuthenticationFilter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Reference data a caller needs before writing: which boards exist, and what categories/tags are
 * valid on them. Read-only — boards are created and administered through the app.
 */
@RestController
@RequestMapping("/api/external/v1")
class ExternalBoardController(
    private val boardService: BoardService,
    private val categoryService: BacklogTaskCategoryService,
    private val tagService: BacklogTaskTagService,
    private val userSettingsService: UserSettingsService,
) {

    /**
     * Identity and context for the calling token. Doubles as the cheapest way to verify a token
     * works, and — more usefully — tells a caller the user's time zone, without which it cannot
     * turn "tomorrow" into the right `YYYY-MM-DD`.
     */
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        authentication: Authentication,
    ): ResponseEntity<ExternalMeResponse> {
        val userId = principal.userId
        val settings = userSettingsService.getOrCreate(userId)
        val scope = if (
            authentication.authorities.any { it.authority == ApiTokenAuthenticationFilter.EXTERNAL_WRITE }
        ) ApiTokenScope.WRITE else ApiTokenScope.READ

        return ResponseEntity.ok(
            ExternalMeResponse(
                userId = userId.toString(),
                displayName = settings.displayName,
                timeZone = settings.timeZone,
                preferredLanguage = settings.preferredLanguage,
                // Null only when the user somehow belongs to no board; write calls would 422 too.
                defaultBoardId = boardService.listBoardsForUser(userId).firstOrNull()?.id?.toString(),
                tokenScope = scope,
            )
        )
    }

    /** The user's boards. The first is the default one that write calls target when none is named. */
    @GetMapping("/boards")
    fun listBoards(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<ExternalBoardResponse>> {
        val boards = boardService.listBoardsForUser(principal.userId)
        return ResponseEntity.ok(
            boards.mapIndexed { index, board -> board.toExternalResponse(isDefault = index == 0) }
        )
    }

    /** Categories, across every board unless `board` narrows it. Each carries its `boardId`. */
    @GetMapping("/categories")
    fun listCategories(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam(required = false) board: String?,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val boardIds = resolveBoardIds(userId, board) ?: return badRequest("'board' is not a valid id.")
        return ResponseEntity.ok(
            boardIds.flatMap { categoryService.getCategories(userId, it) }.map { it.toExternalResponse() }
        )
    }

    /** Tags, across every board unless `board` narrows it. Each carries its `boardId`. */
    @GetMapping("/tags")
    fun listTags(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam(required = false) board: String?,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val boardIds = resolveBoardIds(userId, board) ?: return badRequest("'board' is not a valid id.")
        return ResponseEntity.ok(
            boardIds.flatMap { tagService.getTags(userId, it) }.map { it.toExternalResponse() }
        )
    }

    /**
     * Null means the supplied board id was malformed. An unauthorized board id is not rejected
     * here — the per-board service calls run `requireMember` and raise the usual uniform 403.
     */
    private fun resolveBoardIds(userId: UUID, board: String?): List<UUID>? {
        if (board == null) return boardService.listBoardsForUser(userId).map { it.id }
        val parsed = board.toUuidOrNull() ?: return null
        return listOf(parsed)
    }

    private fun badRequest(detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail))
}

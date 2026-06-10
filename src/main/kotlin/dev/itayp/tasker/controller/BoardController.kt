package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.BoardResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/boards")
class BoardController(private val boardService: BoardService) {

    /** The user's boards, default board first. Board create/rename/delete arrive with the switcher UI. */
    @GetMapping
    fun listBoards(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<BoardResponse>> =
        ResponseEntity.ok(boardService.listBoardsForUser(principal.userId).map { it.toResponse() })
}

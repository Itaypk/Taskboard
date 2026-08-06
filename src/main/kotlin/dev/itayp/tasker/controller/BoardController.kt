package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.CreateBoardRequest
import dev.itayp.tasker.model.request.DuplicateBoardRequest
import dev.itayp.tasker.model.request.UpdateBoardRequest
import dev.itayp.tasker.model.response.BoardResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/boards")
class BoardController(private val boardService: BoardService) {

    /** The user's boards, default board first. */
    @GetMapping
    fun listBoards(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<BoardResponse>> =
        ResponseEntity.ok(boardService.listBoardsForUser(principal.userId).map { it.toResponse() })

    @PostMapping
    fun createBoard(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: CreateBoardRequest,
    ): ResponseEntity<BoardResponse> {
        val board = boardService.createBoard(principal.userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(board.toResponse())
    }

    /** Copies a board's categories, tags, and tasks into a new board owned solely by the caller; returns the new board (201). */
    @PostMapping("/{boardId}/duplicate")
    fun duplicateBoard(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: DuplicateBoardRequest,
    ): ResponseEntity<BoardResponse> {
        val board = boardService.duplicateBoard(principal.userId, boardId, request.name, request.resetTaskStatus)
        return ResponseEntity.status(HttpStatus.CREATED).body(board.toResponse())
    }

    @PatchMapping("/{boardId}")
    fun updateBoard(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: UpdateBoardRequest,
    ): ResponseEntity<BoardResponse> {
        return try {
            ResponseEntity.ok(boardService.updateBoard(principal.userId, boardId, request.name, request.mascot).toResponse())
        } catch (_: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{boardId}")
    fun deleteBoard(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<Void> {
        boardService.deleteBoard(principal.userId, boardId)
        return ResponseEntity.noContent().build()
    }
}

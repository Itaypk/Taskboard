package dev.itayp.tasker.controller

import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.request.SetMemberRoleRequest
import dev.itayp.tasker.model.response.BoardMemberResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardMemberService
import jakarta.validation.Valid
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
@RequestMapping("/api/v1/boards/{boardId}/members")
class BoardMemberController(private val boardMemberService: BoardMemberService) {

    @GetMapping
    fun listMembers(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<List<BoardMemberResponse>> =
        ResponseEntity.ok(boardMemberService.listMembers(principal.userId, boardId).map { it.toResponse() })

    @PatchMapping("/{userId}")
    fun setRole(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: SetMemberRoleRequest,
    ): ResponseEntity<Void> {
        val role = runCatching { BoardRole.valueOf(request.role.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().build()
        }
        boardMemberService.setRole(principal.userId, boardId, userId, role)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{userId}")
    fun removeMember(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        boardMemberService.removeMember(principal.userId, boardId, userId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/leave")
    fun leaveBoard(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<Void> {
        boardMemberService.leaveBoard(principal.userId, boardId)
        return ResponseEntity.noContent().build()
    }
}

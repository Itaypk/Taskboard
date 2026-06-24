package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TagUsage
import dev.itayp.tasker.model.request.UpdateTagRequest
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class BacklogTaskTagService(
    private val tagRepository: BacklogTaskTagRepository,
    private val taskRepository: BacklogTaskRepository,
    private val boardMembershipService: BoardMembershipService,
    private val taskChangeService: BacklogTaskChangeService,
) {

    /**
     * Planner-facing bridge: resolves the user's sole board. Goes away when the planner becomes
     * board-aware (Phase 1 PR 3, `docs/BOARD-SHARING-PHASE1.md`).
     */
    fun getAllForUser(userId: UUID): List<BacklogTaskTag> =
        getTags(userId, boardMembershipService.resolveDefaultBoard(userId))

    fun getTags(userId: UUID, boardId: UUID): List<BacklogTaskTag> {
        boardMembershipService.requireMember(userId, boardId)
        return tagRepository.findAllByBoardId(boardId).map { it.toDomain() }
    }

    /**
     * Tags with their task usage counts, ordered by popularity (most-used first, then alphabetically).
     * Backs both the one-click "popular tags" affordance and the tag manager's usage hint.
     */
    @Transactional(readOnly = true)
    fun listTagsWithUsage(userId: UUID, boardId: UUID): List<TagUsage> {
        boardMembershipService.requireMember(userId, boardId)
        val counts = usageCounts(boardId)
        return tagRepository.findAllByBoardId(boardId)
            .map { TagUsage(it.toDomain(), counts[it.id] ?: 0) }
            .sortedWith(compareByDescending<TagUsage> { it.usageCount }.thenBy { it.tag.label.lowercase() })
    }

    /**
     * Renames and/or recolors a board tag. The change fans out to every task carrying it (tasks embed
     * the tag's label/colour in their API view), so we bump both the tags and tasks watermarks.
     */
    @Transactional
    fun updateTag(userId: UUID, boardId: UUID, id: UUID, request: UpdateTagRequest): TagUsage {
        boardMembershipService.requireMember(userId, boardId)
        val entity = tagRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Tag $id not found")
        entity.label = request.label.trim()
        entity.colorId = TagColor.valueOf(request.colorId.uppercase())
        val saved = tagRepository.save(entity)
        taskChangeService.bumpTags(boardId)
        taskChangeService.bumpWatermark(boardId)
        return TagUsage(saved.toDomain(), usageCounts(boardId)[id] ?: 0)
    }

    /**
     * Deletes a board tag, first detaching it from any tasks that carry it (tags are lightweight
     * labels — we don't force the user to untag everything by hand). Bumps the tasks watermark too
     * when tasks were touched so other open tabs drop the removed tape.
     */
    @Transactional
    fun deleteTag(userId: UUID, boardId: UUID, id: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        val entity = tagRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Tag $id not found")
        val tasksWithTag = taskRepository.findAllByTagId(id)
        if (tasksWithTag.isNotEmpty()) {
            tasksWithTag.forEach { task -> task.tags.removeIf { it.id == id } }
            taskRepository.saveAll(tasksWithTag)
        }
        tagRepository.delete(entity)
        taskChangeService.bumpTags(boardId)
        if (tasksWithTag.isNotEmpty()) taskChangeService.bumpWatermark(boardId)
    }

    private fun usageCounts(boardId: UUID): Map<UUID, Int> =
        taskRepository.countTasksPerTag(boardId).associate { (it[0] as UUID) to (it[1] as Long).toInt() }
}

package dev.itayp.tasker.model

/**
 * A user's role on a board. Deliberately minimal (no viewer/commenter tiers):
 * - [OWNER] created the board and can rename it, manage members, and delete it.
 * - [MEMBER] has full CRUD on the board's tasks.
 *
 * Stored as a string in `board_membership.role`.
 */
enum class BoardRole {
    OWNER,
    MEMBER,
}

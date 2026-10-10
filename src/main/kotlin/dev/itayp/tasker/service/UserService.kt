package dev.itayp.tasker.service

import dev.itayp.tasker.model.CategoryColor
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService(
    private val userSettingsService: UserSettingsService,
    private val boardService: BoardService,
) {

    /**
     * [hints] seed the new user's settings from the registration request (language, time zone).
     * [claimed] distinguishes a real registration from an unclaimed/demo account — see
     * [UserSettingsService.initializeForNewUser] for how it decides the initial AI grant.
     */
    fun initializeNewUser(userId: UUID, hints: RegistrationHints = RegistrationHints.NONE, claimed: Boolean = true) {
        // Every account gets a personal board, which owns the default category set and the
        // account's tasks/tags. A board is "private" until other members are invited.
        boardService.createBoardForOwner(userId, BoardService.DEFAULT_BOARD_NAME)
        userSettingsService.initializeForNewUser(userId, hints, claimed)
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            "Work"     to CategoryColor.SUNSHINE,
            "Personal" to CategoryColor.BLOSSOM,
            "Ideas"    to CategoryColor.MINT,
            "Home"     to CategoryColor.SKY,
            "Errands"  to CategoryColor.LILAC,
            "Health"   to CategoryColor.PEACH,
        )
    }
}

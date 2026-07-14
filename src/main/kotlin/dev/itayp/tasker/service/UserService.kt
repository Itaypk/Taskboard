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
     * [localeHint], when non-null, is a supported language code resolved from the registration
     * request's `Accept-Language`; it seeds the new user's `preferred_language` (docs/I18N.md, D2).
     */
    fun initializeNewUser(userId: UUID, localeHint: String? = null) {
        // Every account gets a personal board, which owns the default category set and the
        // account's tasks/tags. A board is "private" until other members are invited.
        boardService.createBoardForOwner(userId, BoardService.DEFAULT_BOARD_NAME)
        userSettingsService.initializeForNewUser(userId, localeHint)
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

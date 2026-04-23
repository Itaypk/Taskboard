package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService(
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val userSettingsService: UserSettingsService,
) {

    fun initializeNewUser(userId: UUID) {
        DEFAULT_CATEGORIES.forEach { (label, color) ->
            categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.userId = userId
                this.label = label
                this.swatchId = color
            })
        }
        userSettingsService.initializeForNewUser(userId)
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

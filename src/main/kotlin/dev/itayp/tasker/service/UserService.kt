package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import org.springframework.stereotype.Service

@Service
class UserService(private val categoryRepository: BacklogTaskCategoryRepository) {

    fun initializeNewUser(userId: String) {
        DEFAULT_CATEGORIES.forEach { (label, color) ->
            categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.userId = userId
                this.label = label
                this.swatchId = color
            })
        }
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

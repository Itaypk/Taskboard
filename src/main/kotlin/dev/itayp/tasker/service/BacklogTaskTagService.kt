package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BacklogTaskTagService(private val tagRepository: BacklogTaskTagRepository) {

    fun getAllForUser(userId: UUID): List<BacklogTaskTag> =
        tagRepository.findAllByUserId(userId).map { it.toDomain() }
}

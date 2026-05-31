package dev.itayp.tasker.model.response

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity

data class MeResponse(
    val id: String,
    val telegramId: Long?,
    val telegramUsername: String?,
    val telegramFirstName: String?,
    val telegramPhotoUrl: String?,
    val email: String?,
)

fun UserEntity.toMeResponse(crypto: UserCryptoService): MeResponse {
    val ownerId = id ?: error("UserEntity must have an id")
    return MeResponse(
        id = ownerId.toString(),
        telegramId = telegramId,
        telegramUsername = telegramUsername,
        telegramFirstName = crypto.decrypt(ownerId, telegramFirstName),
        telegramPhotoUrl = telegramPhotoUrl,
        email = crypto.decrypt(ownerId, email),
    )
}

package dev.itayp.tasker.model.response

import dev.itayp.tasker.jpa.UserEntity

data class MeResponse(
    val id: String,
    val telegramId: Long?,
    val telegramUsername: String?,
    val telegramFirstName: String?,
    val telegramPhotoUrl: String?,
    val email: String?,
)

fun UserEntity.toMeResponse() = MeResponse(
    id = id?.toString() ?: error("UserEntity must have an id"),
    telegramId = telegramId,
    telegramUsername = telegramUsername,
    telegramFirstName = telegramFirstName,
    telegramPhotoUrl = telegramPhotoUrl,
    email = email,
)

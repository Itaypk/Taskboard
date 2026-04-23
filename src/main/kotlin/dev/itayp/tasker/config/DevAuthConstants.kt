package dev.itayp.tasker.config

import java.util.UUID

/**
 * Deterministic UUID for the local dev user. Shared by DevDataInitializer (seeds the row)
 * and DevAuthController (logs in as this user).
 */
val DEV_USER_ID: UUID = UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray())
const val DEV_USER_TELEGRAM_ID: Long = 0L

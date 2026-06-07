package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserEntity

/** Result of resolving a proven email address to an account (see [UserAuthService.loginByEmail]). */
sealed interface EmailLoginOutcome {
    data class Success(val user: UserEntity) : EmailLoginOutcome

    /**
     * The address is already attached to an account that hasn't verified it, so we won't
     * silently log in or merge. The user should sign in with their existing method and
     * verify the address in settings.
     */
    data object UnverifiedConflict : EmailLoginOutcome
}

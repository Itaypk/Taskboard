package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserEntity

/** Result of resolving a Google sign-in to an account (see [UserAuthService.loginByGoogle]). */
sealed interface GoogleLoginOutcome {
    data class Success(val user: UserEntity) : GoogleLoginOutcome

    /**
     * An account already holds this Google account's email, but we can't safely sign into it: the
     * account hasn't verified the address, Google's claim about it isn't strong enough (see
     * [dev.itayp.tasker.security.google.GoogleAuthData.trustedEmail]), or the account already has a
     * different Google account linked. The user should sign in their usual way and connect Google in
     * Settings, which proves both sides.
     */
    data object EmailConflict : GoogleLoginOutcome
}

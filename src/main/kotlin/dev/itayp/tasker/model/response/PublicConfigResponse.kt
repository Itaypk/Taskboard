package dev.itayp.tasker.model.response

/** Anonymous-safe instance settings for the SPA; see `PublicConfigController`. */
data class PublicConfigResponse(
    val login: LoginMethodsResponse,
    /** False when only existing accounts and operator-listed local users can sign in. */
    val registrationOpen: Boolean,
)

data class LoginMethodsResponse(
    val telegram: Boolean,
    val email: Boolean,
    val password: Boolean,
    val demo: Boolean,
)

package dev.itayp.tasker.model.response

/** Anonymous-safe instance settings for the SPA; see `PublicConfigController`. */
data class PublicConfigResponse(
    val login: LoginMethodsResponse,
    /** False when only existing accounts and operator-listed local users can sign in. */
    val registrationOpen: Boolean,
    val branding: BrandingResponse,
    /**
     * True when AI features are on and every AI call is restricted to zero-data-retention endpoints,
     * so the landing copy only makes that promise when it holds.
     */
    val aiZeroDataRetention: Boolean,
)

/** What the instance calls itself, and where users can reach whoever runs it. */
data class BrandingResponse(
    val name: String,
    val supportEmail: String,
    val abuseEmail: String,
)

data class LoginMethodsResponse(
    val telegram: Boolean,
    val email: Boolean,
    val password: Boolean,
    val demo: Boolean,
)

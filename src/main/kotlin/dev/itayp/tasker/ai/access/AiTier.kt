package dev.itayp.tasker.ai.access

/**
 * Per-user AI subscription tier. The tier maps to the rolling-window token budget the
 * [AiAccessService] enforces via the call gate.
 *
 * Tier storage is a free-form string on `user_settings.ai_tier` so adding a tier doesn't
 * require a migration; unknown strings degrade to [STANDARD].
 */
enum class AiTier(val tierName: String, val monthlyTokenLimit: Long?) {
    /** Default for every new user. */
    STANDARD("standard", 1_000_000L),

    /** No limit — for the project owner / beta operators. */
    UNLIMITED("unlimited", null);

    companion object {
        fun fromName(name: String?): AiTier =
            entries.firstOrNull { it.tierName.equals(name, ignoreCase = true) } ?: STANDARD
    }
}

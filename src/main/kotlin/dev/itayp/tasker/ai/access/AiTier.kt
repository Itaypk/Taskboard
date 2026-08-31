package dev.itayp.tasker.ai.access

/**
 * Per-user AI subscription tier. The tier maps to the rolling-window token budget the
 * [AiAccessService] enforces via the call gate.
 *
 * Tier storage is a free-form string on `user_settings.ai_tier` so adding a tier doesn't
 * require a migration; unknown strings degrade to [NONE] — access is opt-in (granted by
 * [AiAccessService.requireAiTierGranted]), so an unrecognized value must never be read as access.
 */
enum class AiTier(val tierName: String, val monthlyTokenLimit: Long?, val grantsAccess: Boolean = true) {
    /** Granted at registration while the operator-configured cap has headroom. */
    STANDARD("standard", 1_000_000L),

    /** No limit — for the project owner / beta operators. */
    UNLIMITED("unlimited", null),

    /**
     * Always granted to unclaimed/demo accounts, uncounted against the cap — a demo signup never
     * proves it's a real user, so its budget is a fraction of [STANDARD] rather than the same
     * allowance, bounding worst-case cost from a burst of throwaway signups during a marketing push.
     */
    DEMO("demo", 100_000L),

    /**
     * No AI access. Not reachable from registration — every new account is granted [DEMO] or
     * better (see `UserSettingsService.initializeForNewUser`) — but kept as a manual admin lever
     * (e.g. to suspend a single account) and as the safe fallback for an unrecognized tier string.
     */
    NONE("none", 0L, grantsAccess = false);

    companion object {
        fun fromName(name: String?): AiTier =
            entries.firstOrNull { it.tierName.equals(name, ignoreCase = true) } ?: NONE
    }
}

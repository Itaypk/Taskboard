package dev.itayp.tasker.service

/**
 * Stable, user-facing categories for an import failure. The raw exception message stays useful for
 * logs and as a secondary detail, but the category is what the UI maps to a plain-language headline
 * (and decides whether to offer a "contact support" link). Keep these in sync with the frontend's
 * `ImportErrorCategory` (`tasker-frontend/src/components/ImportResultDialog.tsx`).
 */
enum class ImportErrorCategory {
    /** The file isn't a readable Backlog export (bad shape, unknown enum, out-of-range index, bad date). */
    CORRUPTED_FILE,

    /** The export's `formatVersion` isn't the one this build accepts. */
    UNSUPPORTED_VERSION,

    /** The account already holds the user's own data; import only runs on a fresh account. */
    ACCOUNT_NOT_EMPTY,

    /** An unexpected server-side failure (e.g. a constraint clash that slipped past the guards). */
    INTERNAL_ERROR,
}

/**
 * A rejected import tagged with a [category] the API maps to an HTTP status and a machine-readable
 * code (see `AccountController`). [message] is the technical detail — fine for logs and as the
 * dialog's secondary line, but never the primary message shown to a user.
 */
class ImportException(
    val category: ImportErrorCategory,
    message: String,
) : RuntimeException(message)

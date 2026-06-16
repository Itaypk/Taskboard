package dev.itayp.tasker.model

/**
 * The little character shown in the corner of a board. Cosmetic and per-board (shared by all
 * members), so it is stored in plaintext. The [id] is the on-the-wire value the frontend maps to an
 * image; a null/unknown stored value is read as [DEFAULT].
 */
enum class BoardMascot(val id: String) {
    PINEAPPLE("pineapple"),
    MR_ROBOTO("mr_roboto"),
    STATIONERY("stationery");

    companion object {
        val DEFAULT = PINEAPPLE

        /** Canonical id for a stored/requested value, falling back to the default for null or unknown input. */
        fun normalize(value: String?): String =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }?.id ?: DEFAULT.id
    }
}

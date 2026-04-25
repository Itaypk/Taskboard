package dev.itayp.tasker.service

/**
 * Generates lexicographic sort keys for stable, user-customizable task ordering.
 *
 * Keys use the printable ASCII range `0`–`z` (codes 48–122, 75 characters wide)
 * as the "alphabet".  Lexicographic string comparison matches the SQL
 * `ORDER BY sort_key ASC` behaviour, so no special DB collation is required.
 *
 * ## Properties
 * - Any two distinct keys always have a valid midpoint key between them.
 * - Keys only grow longer under adversarial same-spot insertion (worst case
 *   +1 character per operation). At 50 characters the caller should rebalance.
 * - Initial keys produced by [spreadKeys] are single characters with large gaps
 *   between them, maximising future headroom.
 */
object SortKeyGenerator {

    // Printable ASCII from '0' (48) to 'z' (122) inclusive — 75 symbols.
    private const val FIRST = '0'.code   // 48
    private const val LAST  = 'z'.code   // 122
    private const val RANGE = LAST - FIRST + 1   // 75

    /** The middle character of the alphabet — used as the seed for the first key. */
    val INITIAL: String = charCode((FIRST + LAST) / 2).toString()   // 'W' (85)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a key that sorts strictly before [b].
     * Equivalent to [midpoint] between an imaginary key just before the alphabet and [b].
     */
    fun before(b: String): String = midpoint("", b)

    /**
     * Returns a key that sorts strictly after [a].
     * Appends a midpoint character so the result is longer and always larger.
     */
    fun after(a: String): String =
        // Append mid-alphabet char; the result is lexicographically after `a`
        // because it shares the same prefix and is then extended.
        a + INITIAL

    /**
     * Returns a key strictly between [a] and [b] lexicographically.
     *
     * Precondition: [a] < [b] (or [a] is empty meaning "beginning of space").
     */
    fun midpoint(a: String, b: String): String {
        // Pad the shorter string conceptually with FIRST - 1 (just below range)
        // so we can do digit-by-digit arithmetic.
        val len = maxOf(a.length, b.length) + 1
        val result = StringBuilder()

        // We'll compute (a + b) / 2 digit by digit from right to left, then reverse.
        // Simpler: build digit arrays and do big-integer average.

        val da = IntArray(len) { i -> if (i < a.length) a[i].code - FIRST else 0 }
        val db = IntArray(len) { i -> if (i < b.length) b[i].code - FIRST else RANGE }

        // Sum da + db
        val sum = IntArray(len)
        var c = 0
        for (i in len - 1 downTo 0) {
            val s = da[i] + db[i] + c
            sum[i] = s % (RANGE * 2)
            c = s / (RANGE * 2)
        }

        // Divide sum by 2 (right-shift in base RANGE*2)
        var rem = 0
        val half = IntArray(len)
        for (i in 0 until len) {
            val cur = rem * (RANGE * 2) + sum[i]
            half[i] = cur / 2
            rem = cur % 2
        }

        // Convert back to characters, stripping trailing FIRST ('0') characters
        // but keeping at least one character.
        var end = len - 1
        while (end > 0 && half[end] == 0) end--

        for (i in 0..end) {
            result.append(charCode(FIRST + half[i]))
        }

        val mid = result.toString()

        // Safety: if the result collapsed to equal `a`, append a mid-char.
        return if (mid <= a) a + INITIAL else mid
    }

    /**
     * Produces [count] evenly-spaced sort keys intended for bulk assignment
     * (e.g. initial seeding or rebalancing).
     *
     * Keys are single or two-character strings spread across the alphabet with
     * large gaps so there is maximum room for future insertions between them.
     */
    fun spreadKeys(count: Int): List<String> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(INITIAL)

        // Distribute `count` steps evenly across RANGE^2 = 75^2 = 5625 slots.
        // We represent each slot as a two-char key so the space is large enough.
        val total = RANGE * RANGE  // 5625
        val step = total / (count + 1)

        return (1..count).map { i ->
            val slot = i * step
            val hi = slot / RANGE
            val lo = slot % RANGE
            buildString {
                append(charCode(FIRST + hi))
                if (lo > 0) append(charCode(FIRST + lo))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun charCode(code: Int): Char {
        val clamped = code.coerceIn(FIRST, LAST)
        return clamped.toChar()
    }
}

package dev.itayp.tasker.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SortKeyGeneratorTest {

    // -------------------------------------------------------------------------
    // midpoint
    // -------------------------------------------------------------------------

    @Test
    fun `midpoint between two single-char keys is strictly between them`() {
        val a = "0"
        val b = "z"
        val mid = SortKeyGenerator.midpoint(a, b)
        assertTrue(mid > a, "midpoint '$mid' should be > '$a'")
        assertTrue(mid < b, "midpoint '$mid' should be < '$b'")
    }

    @Test
    fun `midpoint between adjacent keys extends the string`() {
        val a = "A"
        val b = "B"
        val mid = SortKeyGenerator.midpoint(a, b)
        assertTrue(mid > a, "midpoint '$mid' should be > '$a'")
        assertTrue(mid < b, "midpoint '$mid' should be < '$b'")
        assertTrue(mid.length >= 2, "midpoint '$mid' should be longer than single char for adjacent inputs")
    }

    @Test
    fun `midpoint with empty first arg produces key before second`() {
        val b = "M"
        val mid = SortKeyGenerator.midpoint("", b)
        assertTrue(mid < b, "midpoint '$mid' with empty 'a' should be < '$b'")
        assertTrue(mid.isNotEmpty())
    }

    @Test
    fun `midpoint is deterministic`() {
        val a = "D"
        val b = "P"
        assertEquals(SortKeyGenerator.midpoint(a, b), SortKeyGenerator.midpoint(a, b))
    }

    // -------------------------------------------------------------------------
    // before / after
    // -------------------------------------------------------------------------

    @Test
    fun `before returns a key strictly less than given key`() {
        val key = "M"
        val result = SortKeyGenerator.before(key)
        assertTrue(result < key, "'$result' should be < '$key'")
    }

    @Test
    fun `after returns a key strictly greater than given key`() {
        val key = "M"
        val result = SortKeyGenerator.after(key)
        assertTrue(result > key, "'$result' should be > '$key'")
    }

    @Test
    fun `after of after is still monotonically increasing`() {
        var key = SortKeyGenerator.INITIAL
        val keys = mutableListOf(key)
        repeat(20) {
            key = SortKeyGenerator.after(key)
            keys.add(key)
        }
        for (i in 1 until keys.size) {
            assertTrue(keys[i] > keys[i - 1],
                "keys[$i]='${keys[i]}' should be > keys[${i-1}]='${keys[i-1]}'")
        }
    }

    // -------------------------------------------------------------------------
    // spreadKeys
    // -------------------------------------------------------------------------

    @Test
    fun `spreadKeys produces the correct count`() {
        assertEquals(0, SortKeyGenerator.spreadKeys(0).size)
        assertEquals(1, SortKeyGenerator.spreadKeys(1).size)
        assertEquals(8, SortKeyGenerator.spreadKeys(8).size)
        assertEquals(100, SortKeyGenerator.spreadKeys(100).size)
    }

    @Test
    fun `spreadKeys produces strictly ordered keys`() {
        val keys = SortKeyGenerator.spreadKeys(10)
        for (i in 1 until keys.size) {
            assertTrue(keys[i] > keys[i - 1],
                "keys[$i]='${keys[i]}' should be > keys[${i-1}]='${keys[i-1]}'")
        }
    }

    @Test
    fun `spreadKeys keys are all distinct`() {
        val keys = SortKeyGenerator.spreadKeys(50)
        assertEquals(keys.size, keys.toSet().size, "all spread keys should be distinct")
    }

    // -------------------------------------------------------------------------
    // Stress test: repeated same-spot insertion
    // -------------------------------------------------------------------------

    @Test
    fun `100 consecutive same-spot insertions maintain order and stay under 50 chars`() {
        // Simulate always inserting between the first and second element.
        val keys = mutableListOf("0", "z")
        repeat(100) {
            val newKey = SortKeyGenerator.midpoint(keys[0], keys[1])
            assertNotEquals(keys[0], newKey, "new key should differ from left neighbour")
            assertNotEquals(keys[1], newKey, "new key should differ from right neighbour")
            assertTrue(newKey > keys[0])
            assertTrue(newKey < keys[1])
            keys.add(1, newKey)
        }

        // After 100 same-spot insertions, key lengths should remain manageable.
        // The rebalance threshold is 50; check the average stays well below it.
        val maxLen = keys.maxOf { it.length }
        assertTrue(maxLen < 50, "max key length after 100 insertions was $maxLen, expected < 50")
    }
}

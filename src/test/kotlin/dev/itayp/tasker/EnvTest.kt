package dev.itayp.tasker

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Loads key=value pairs from `.env.test` in the project root.
 * Tests call [requireEnv] to fetch secrets; the call skips the test via a
 * JUnit assumption when any key is absent, so the suite stays green without
 * the file present.
 */
object EnvTest {

    private val env: Map<String, String> by lazy { load() }

    /**
     * Returns the values for every [keys] in the same order, or skips the
     * current test with an informative message if any key is missing.
     */
    fun requireEnv(vararg keys: String): Map<String, String> {
        val missing = keys.filter { it !in env }
        assumeTrue(
            missing.isEmpty(),
            "Skipping: add the following keys to .env.test: ${missing.joinToString(", ")}",
        )
        return keys.associateWith { env.getValue(it) }
    }

    private fun load(): Map<String, String> {
        val file = File(System.getProperty("user.dir"), ".env.test")
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                var value = line.substring(idx + 1).trim()
                // Strip optional surrounding quotes
                if (value.length >= 2 &&
                    ((value.startsWith('"') && value.endsWith('"')) ||
                        (value.startsWith('\'') && value.endsWith('\'')))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                key to value
            }
            .toMap()
    }
}

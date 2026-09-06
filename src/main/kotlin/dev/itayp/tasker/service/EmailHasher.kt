package dev.itayp.tasker.service

import java.security.MessageDigest

/**
 * Hashes an email address for the unique-by-email constraint without storing the
 * plaintext in an indexable column. Input is normalized (`trim().lowercase()`)
 * so trivial typos in casing/whitespace don't bypass the uniqueness check.
 *
 * SHA-256 is fine here despite low input entropy — we're not hashing for secrecy,
 * just for a fixed-width unique handle. An attacker with the DB cannot reverse a
 * particular hash to plaintext without a targeted guess against a known address,
 * which is no worse than holding the encrypted email blob.
 */
object EmailHasher {
    fun hash(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

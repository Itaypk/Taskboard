package dev.itayp.tasker.channel.whatsapp

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * WhatsApp Cloud API (`tasker.whatsapp`, GitHub issue #268, plan in `docs/MESSAGING-CHANNELS.md`).
 *
 * Today only the webhook exists, and it only logs what Meta delivers (issue #410). Off unless
 * both the verify token and the app secret are set: without the secret no delivery can be
 * authenticated, so the endpoint answers 404 rather than accept unverifiable bodies.
 */
@ConfigurationProperties("tasker.whatsapp")
data class WhatsAppProperties(
    val enabled: Boolean = false,
    /**
     * The token typed into the Meta app's webhook settings, echoed back by Meta in the
     * subscription handshake (`hub.verify_token`). It travels in a query string, so it ends up in
     * access logs: treat it as low-value and never reuse another secret for it.
     */
    val verifyToken: String = "",
    /** The Meta app secret: the HMAC key of every delivery's `X-Hub-Signature-256`. */
    val appSecret: String = "",
    /**
     * Our WhatsApp business phone number id. When set, deliveries for any other number are logged
     * as foreign. Optional while the webhook only logs.
     */
    val phoneNumberId: String = "",
    /**
     * Log each delivery's full raw body. A testing aid only: bodies carry phone numbers and
     * message text, which must otherwise never reach the logs. Warned about at startup.
     */
    val logPayloads: Boolean = false,
) {
    /** Whether the webhook answers at all: enabled, with both secrets set. */
    val active: Boolean get() = enabled && verifyToken.isNotBlank() && appSecret.isNotBlank()

    /** Never print the secrets (data classes include every field in toString). */
    override fun toString(): String =
        "WhatsAppProperties(enabled=$enabled, verifyToken=${mask(verifyToken)}, appSecret=${mask(appSecret)}, " +
            "phoneNumberId=$phoneNumberId, logPayloads=$logPayloads)"

    private fun mask(value: String) = if (value.isEmpty()) "unset" else "***"
}

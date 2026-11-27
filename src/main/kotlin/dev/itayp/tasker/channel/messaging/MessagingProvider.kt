package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.ChannelType

/**
 * A messaging channel this instance offers for linking (WhatsApp today). A provider registers
 * itself as a bean only when it is configured, so "which channels can a user link" is simply the
 * set of beans.
 */
interface MessagingProvider {
    val channel: ChannelType

    /**
     * How the user sends [code] to the bot from their chat account. For WhatsApp, a `wa.me` link
     * that opens the chat with the code already typed.
     */
    fun linkInstructions(code: String): LinkInstructions
}

/**
 * @param url opens the chat with the bot, the code prefilled when the platform allows it. The SPA
 *   also shows it as a QR code for desktop users.
 */
data class LinkInstructions(val url: String?)

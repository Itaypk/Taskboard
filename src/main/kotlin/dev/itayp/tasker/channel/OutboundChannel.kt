package dev.itayp.tasker.channel

/**
 * One-way push channel — sends messages without expecting a reply.
 * Contrast with [ConversationChannel], which is bidirectional.
 */
interface OutboundChannel {
    fun send(message: OutboundMessage)
}

/** Marker interface for all outbound message types. Subtypes live in channel sub-packages. */
interface OutboundMessage

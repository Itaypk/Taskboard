package dev.itayp.tasker.crypto

import org.springframework.stereotype.Component

/**
 * Lookup key for messaging-channel links (`ChannelLinkService`): both the chat account's id (a
 * WhatsApp business-scoped user id, low-entropy and close to a phone number) and the short code a
 * user sends to link it are hashed with a [KekDerivedHasher].
 */
@Component
class ChannelLinkHasher(properties: DataEncryptionProperties) :
    KekDerivedHasher(properties, "tasker-channel-link-v1")

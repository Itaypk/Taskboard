package dev.itayp.tasker.crypto

import org.springframework.stereotype.Component

/**
 * Lookup key for inbound email address tokens. An address token has to stay short enough to type
 * into a To field (10 base32 characters, ~50 bits), and 2^50 SHA-256 guesses against a leaked dump
 * is about a day on one GPU, hence a [KekDerivedHasher].
 */
@Component
class InboundMailTokenHasher(properties: DataEncryptionProperties) :
    KekDerivedHasher(properties, "tasker-inbound-mail-token-v1")

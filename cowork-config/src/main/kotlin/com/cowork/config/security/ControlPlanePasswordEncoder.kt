package com.cowork.config.security

import org.springframework.security.crypto.password.AbstractValidatingPasswordEncoder
import java.security.MessageDigest
import java.util.HexFormat

// Control-plane passwords are 256-bit random tokens, so a slow KDF adds per-request CPU cost without guessing resistance.
class ControlPlanePasswordEncoder : AbstractValidatingPasswordEncoder() {
    override fun encodeNonNullPassword(rawPassword: String): String = HexFormat.of().formatHex(digest(rawPassword))

    override fun matchesNonNull(rawPassword: String, encodedPassword: String): Boolean =
        MessageDigest.isEqual(digest(rawPassword), HexFormat.of().parseHex(encodedPassword))

    private fun digest(rawPassword: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rawPassword.toByteArray(Charsets.UTF_8))
}

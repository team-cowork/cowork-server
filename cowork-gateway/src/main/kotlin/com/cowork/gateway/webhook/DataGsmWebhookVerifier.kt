package com.cowork.gateway.webhook

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class DataGsmWebhookVerifier(
    @param:Value("\${webhook.datagsm-secret}") secret: String,
    private val objectMapper: ObjectMapper,
) {
    private val signingKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    fun validSignature(body: ByteArray, header: String?): Boolean {
        if (header?.startsWith(PREFIX) != true) return false
        val provided = runCatching { HexFormat.of().parseHex(header.removePrefix(PREFIX)) }.getOrNull()
            ?: return false
        if (provided.size != 32) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)
        return MessageDigest.isEqual(mac.doFinal(body), provided)
    }

    fun validEnvelope(body: ByteArray): Boolean = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
        val node = objectMapper.readTree(body)
        val idNode = node?.get("id")
        val eventNode = node?.get("event")
        val timestampNode = node?.get("timestamp")
        val id = idNode?.asString().orEmpty()
        val event = eventNode?.asString().orEmpty()
        val timestamp = node?.get("timestamp")?.asString().orEmpty()
        val data = node?.get("data")
        node?.isObject == true && idNode?.isString == true && id.isNotBlank() && id == id.trim() && id.length <= 255 &&
            eventNode?.isString == true && event.isNotBlank() && event.length <= 64 &&
            timestampNode?.isString == true && validTimestamp(OffsetDateTime.parse(timestamp, RFC3339)) &&
            (
                event != "student.updated" ||
                    (data?.isObject == true && data.get("new")?.isArray == true && data.get("new").size() > 0)
                )
    }.getOrDefault(false)

    private fun validTimestamp(timestamp: OffsetDateTime): Boolean =
        timestamp.year in 1000..9999 && !timestamp.toInstant().isAfter(Instant.now().plus(MAX_FUTURE_SKEW))

    private companion object {
        const val PREFIX = "sha256="

        // Authorization keeps future-dated stream entries pending, so this bounds how long they are retried.
        val MAX_FUTURE_SKEW: Duration = Duration.ofHours(1)

        // Matches Go time.RFC3339Nano parsing in cowork-authorization: seconds are required.
        val RFC3339 = DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "Z")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT)
    }
}

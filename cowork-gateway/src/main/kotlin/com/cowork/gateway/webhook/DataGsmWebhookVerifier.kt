package com.cowork.gateway.webhook

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class DataGsmWebhookVerifier(
    @param:Value("\${webhook.datagsm-secret:}") private val secret: String,
    private val objectMapper: ObjectMapper,
) {
    fun configured(): Boolean = secret.isNotEmpty()

    fun validSignature(body: ByteArray, header: String?): Boolean {
        if (!configured() || header?.startsWith(PREFIX) != true) return false
        val provided = runCatching { HexFormat.of().parseHex(header.removePrefix(PREFIX)) }.getOrNull()
            ?: return false
        if (provided.size != 32) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
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
            timestampNode?.isString == true && OffsetDateTime.parse(timestamp).year in 1000..9999 &&
            (
                event != "student.updated" ||
                    (data?.isObject == true && data.get("new")?.isArray == true && data.get("new").size() > 0)
                )
    }.getOrDefault(false)

    private companion object {
        const val PREFIX = "sha256="
    }
}

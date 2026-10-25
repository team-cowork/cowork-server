package com.cowork.preference.service

import com.cowork.preference.domain.WebhookRequestException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookUrlSigner(encodedKey: String?) {
    private val key = encodedKey?.let {
        runCatching { Base64.getDecoder().decode(it) }.getOrNull()?.takeIf { bytes -> bytes.size == 32 }
    }

    fun requireReady() {
        if (key == null) throw WebhookRequestException(503, "웹훅 실행 경로 서명을 준비하고 있습니다.")
    }

    fun executionPath(executionId: String): String {
        requireReady()
        val id = UUID.fromString(executionId)
        check(id.toString() == executionId && id.version() == 4 && id.variant() == 2) {
            "Webhook execution ID must be canonical UUIDv4"
        }
        val signature = hmacSha256(checkNotNull(key), "cowork:webhook:execute:v1:$executionId")
        return "/api/chat/chat/webhooks/$executionId/$signature/messages"
    }

    companion object {
        internal fun hmacSha256(key: ByteArray, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

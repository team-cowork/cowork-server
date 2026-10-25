package com.cowork.preference.service

import com.cowork.preference.domain.WebhookRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class WebhookUrlSignerTest {
    private val executionId = "6cf6eda2-99a4-4f45-9bc9-14292c607df0"
    private val key = "CwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCws="

    @Test
    fun `HMAC SHA256은 RFC 4231의 표준 벡터와 일치한다`() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            WebhookUrlSigner.hmacSha256(ByteArray(20) { 0x0b }, "Hi There"),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            WebhookUrlSigner.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?"),
        )
    }

    @Test
    fun `실행 URL은 Gateway와 공유하는 도메인 구분 서명 벡터를 사용한다`() {
        val signature = "9e6e884db755dbd96a9fffb1e149bfa829ba24abb4493bc6b7a77e0a2a5c5051"
        assertEquals(
            "/api/chat/chat/webhooks/$executionId/$signature/messages",
            WebhookUrlSigner(key).executionPath(executionId),
        )
    }

    @Test
    fun `UUID나 서명키가 바뀌면 실행 서명도 바뀐다`() {
        val signer = WebhookUrlSigner(key)
        val originalSignature = signer.executionPath(executionId).split('/')[6]
        assertNotEquals(originalSignature,
            signer.executionPath("5b30857f-0bfa-48b5-ac0b-5c64e28078d1").split('/')[6])
        val otherSigner = WebhookUrlSigner(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        assertNotEquals(originalSignature, otherSigner.executionPath(executionId).split('/')[6])
    }

    @Test
    fun `누락되거나 올바른 32바이트 Base64가 아닌 서명키는 503으로 거부한다`() {
        val invalidKeys = listOf(null, "", "not-base64",
            Base64.getEncoder().encodeToString(ByteArray(31)), Base64.getEncoder().encodeToString(ByteArray(33)))
        for (invalidKey in invalidKeys) {
            val failure = assertThrows(WebhookRequestException::class.java) {
                WebhookUrlSigner(invalidKey).executionPath(executionId)
            }
            assertEquals(503, failure.status)
        }
    }
}

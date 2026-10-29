package com.cowork.gateway.webhook

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DataGsmWebhookVerifierTest :
    DescribeSpec({
        val secret = "test-only-secret"
        val verifier = DataGsmWebhookVerifier(secret, JsonMapper.builder().build())
        val body = """
            {
              "id":"event-1",
              "event":"student.updated",
              "timestamp":"2026-10-29T01:00:00Z",
              "data":{"new":[{}]}
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        val signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body))

        describe("DataGSM 서명과 봉투 검증") {
            it("원본 본문과 HMAC이 일치할 때만 접수 후보가 된다") {
                verifier.validSignature(body, signature) shouldBe true
                verifier.validEnvelope(body) shouldBe true
                verifier.validSignature(body + 1, signature) shouldBe false
                verifier.validSignature(body, "sha256=invalid") shouldBe false
            }
            it("잘못된 봉투와 UTF-8은 Redis에 넣기 전에 거부한다") {
                verifier.validEnvelope("{}".toByteArray()) shouldBe false
                val numericId = body.toString(StandardCharsets.UTF_8)
                    .replace("\"event-1\"", "3").toByteArray(StandardCharsets.UTF_8)
                verifier.validEnvelope(numericId) shouldBe false
                verifier.validEnvelope(byteArrayOf(0xc3.toByte(), 0x28)) shouldBe false
            }
            it("알 수 없는 이벤트는 Authorization과 같이 무시할 수 있도록 허용한다") {
                val unknown = """{"id":"event-2","event":"other","timestamp":"2026-10-29T01:00:00Z"}"""
                verifier.validEnvelope(unknown.toByteArray(StandardCharsets.UTF_8)) shouldBe true
            }
        }
    })

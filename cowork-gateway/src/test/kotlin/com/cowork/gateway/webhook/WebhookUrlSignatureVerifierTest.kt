package com.cowork.gateway.webhook

import com.cowork.gateway.webhook.WebhookUrlSignatureVerifier.Verification
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

class WebhookUrlSignatureVerifierTest :
    DescribeSpec({
        // Preference 발급기와 공유하는 테스트 전용 벡터. 실제 서명키가 아니다.
        val key = "CwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCws="
        val executionId = "6cf6eda2-99a4-4f45-9bc9-14292c607df0"
        val signature = "9e6e884db755dbd96a9fffb1e149bfa829ba24abb4493bc6b7a77e0a2a5c5051"

        describe("WebhookUrlSignatureVerifier 클래스의") {
            describe("verify 메서드는") {
                context("발급기가 만든 서명인 경우") {
                    it("공유 벡터를 검증하고 UUID 대소문자는 같은 식별자로 처리한다") {
                        // Given
                        val verifier = WebhookUrlSignatureVerifier(key)

                        // When & Then
                        verifier.verify(executionId, signature) shouldBe Verification.VALID
                        verifier.verify(executionId.uppercase(), signature) shouldBe Verification.VALID
                    }
                }

                context("URL 또는 서명키가 변조된 경우") {
                    it("다른 UUID와 서명 및 키로는 실행을 허용하지 않는다") {
                        // Given
                        val verifier = WebhookUrlSignatureVerifier(key)
                        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { 12 })

                        // When & Then
                        verifier.verify("7" + executionId.drop(1), signature) shouldBe Verification.INVALID
                        verifier.verify(executionId, signature.dropLast(1) + "0") shouldBe Verification.INVALID
                        WebhookUrlSignatureVerifier(otherKey).verify(executionId, signature) shouldBe
                            Verification.INVALID
                    }
                }

                context("UUID 또는 서명 형식이 잘못된 경우") {
                    it("서명키 설정 여부에 관계없이 잘못된 요청으로 거부한다") {
                        // Given
                        val verifier = WebhookUrlSignatureVerifier(null)
                        val invalidIds =
                            listOf(
                                null,
                                "1",
                                executionId.replace("-4f45-", "-3f45-"),
                                executionId.replace("-9bc9-", "-7bc9-"),
                                executionId + "0",
                            )
                        val invalidSignatures =
                            listOf(
                                null,
                                "",
                                signature.dropLast(1),
                                signature + "0",
                                signature.uppercase(),
                                "g".repeat(64),
                            )

                        // When & Then
                        invalidIds.forEach { verifier.verify(it, signature) shouldBe Verification.INVALID }
                        invalidSignatures.forEach { verifier.verify(executionId, it) shouldBe Verification.INVALID }
                    }
                }

                context("서명키가 없거나 Base64 32바이트 키가 아닌 경우") {
                    it("정상 형식 요청에도 검증 불가를 반환한다") {
                        // Given
                        val invalidKeys =
                            listOf(
                                null,
                                "",
                                "not-base64!",
                                Base64.getEncoder().encodeToString(ByteArray(31)),
                                Base64.getEncoder().encodeToString(ByteArray(33)),
                            )

                        // When & Then
                        invalidKeys.forEach {
                            WebhookUrlSignatureVerifier(it).verify(executionId, signature) shouldBe
                                Verification.UNAVAILABLE
                        }
                    }
                }
            }
        }
    })

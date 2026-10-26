package com.cowork.gateway.webhook

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class WebhookSignatureGatewayFilterFactoryTest :
    DescribeSpec({
        describe("WebhookSignatureGatewayFilterFactory 클래스는") {
            context("생성되면") {
                it("거부 사유별 카운터를 첫 거부 전에 0으로 등록한다") {
                    // Given
                    val registry = SimpleMeterRegistry()

                    // When
                    WebhookSignatureGatewayFilterFactory("", registry)

                    // Then
                    listOf("invalid", "unavailable").forEach { reason ->
                        registry.find("gateway.webhook.signature.rejections").tag("reason", reason).counter()
                            ?.count() shouldBe 0.0
                    }
                }
            }
        }
    })

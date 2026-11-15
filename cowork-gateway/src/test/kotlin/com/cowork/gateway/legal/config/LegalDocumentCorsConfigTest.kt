package com.cowork.gateway.legal.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.cors.CorsConfiguration

class LegalDocumentCorsConfigTest :
    DescribeSpec({
        fun exchange(path: String) = MockServerWebExchange.from(
            MockServerHttpRequest.get("https://api.cowork.test$path").header("Origin", "https://web.cowork.test"),
        )

        describe("legalDocumentCorsSource는") {
            context("전역 CORS 정책이 있으면") {
                val global = CorsConfiguration().apply {
                    allowedOrigins = listOf("https://web.cowork.test")
                    allowCredentials = true
                }
                val source = LegalDocumentCorsConfig.legalDocumentCorsSource(
                    GlobalCorsProperties().apply { corsConfigurations["/**"] = global },
                )

                it("약관 문서 경로에 같은 정책을 적용한다") {
                    val config = source.getCorsConfiguration(exchange("/api/legal/terms-of-service"))
                    config?.checkOrigin("https://web.cowork.test") shouldBe "https://web.cowork.test"
                    config?.allowCredentials shouldBe true
                }
                it("다른 경로에는 적용하지 않는다") {
                    source.getCorsConfiguration(exchange("/api/health")).shouldBeNull()
                }
            }
            context("전역 CORS 정책이 없으면") {
                it("약관 문서 경로에도 정책을 적용하지 않는다") {
                    LegalDocumentCorsConfig.legalDocumentCorsSource(GlobalCorsProperties())
                        .getCorsConfiguration(exchange("/api/legal/license"))
                        .shouldBeNull()
                }
            }
        }
    })

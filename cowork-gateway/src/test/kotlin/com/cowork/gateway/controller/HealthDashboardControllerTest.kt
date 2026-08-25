package com.cowork.gateway.controller

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

// TODO(temporary health dashboard): Remove this test class with the temporary /health dashboard.
class HealthDashboardControllerTest :
    DescribeSpec({
        val client = WebTestClient
            .bindToController(HealthDashboardController())
            .build()

        // TODO(temporary health dashboard): Remove this test block with the temporary /health dashboard.
        describe("임시 Health Dashboard는") {
            it("캐시하지 않는 자체 완결 HTML을 반환한다") {
                val body = client
                    .get()
                    .uri("/health")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                    .expectHeader()
                    .cacheControl(CacheControl.noStore())
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody

                requireNotNull(body)
                body shouldContain "/api/health"
                body shouldContain "REFRESH_INTERVAL_MS = 10000"
                body shouldContain "다시 확인"
                body shouldContain "TODO(temporary health dashboard)"
                body shouldNotContain "https://"
                body shouldNotContain "http://"
            }
        }
    })

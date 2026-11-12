package com.cowork.gateway.response.handler

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

class ApiExceptionHandlerTest :
    DescribeSpec({
        val mapper = JsonMapper.builder().build()
        val handler = ApiExceptionHandler(mapper, ApiResponseWrapperProperties())

        describe("API 예외 응답") {
            it("잘못된 요청에는 내부 예외 사유 대신 오류 안내를 반환한다") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/health"))

                handler.handle(exchange, ResponseStatusException(HttpStatus.BAD_REQUEST, "internal detail")).block()

                val body = mapper.readTree(exchange.response.bodyAsString.block())
                exchange.response.statusCode shouldBe HttpStatus.BAD_REQUEST
                exchange.response.headers.cacheControl shouldBe "no-store"
                body.get("code").asInt() shouldBe 400
                body.get("message").asString() shouldBe "유효하지 않은 요청입니다."
                body.has("data") shouldBe false
            }

            it("예상하지 못한 오류에는 내부 정보를 제외한 500 응답을 반환한다") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/health"))

                handler.handle(exchange, IllegalStateException("internal detail")).block()

                val body = mapper.readTree(exchange.response.bodyAsString.block())
                exchange.response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                body.get("code").asInt() shouldBe 500
                body.get("message").asString() shouldBe "요청을 처리할 수 없습니다."
                body.has("data") shouldBe false
            }

            it("지원하지 않는 메서드에는 Allow 헤더와 오류 안내를 반환한다") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/health"))

                handler.handle(exchange, MethodNotAllowedException("POST", listOf(HttpMethod.GET))).block()

                exchange.response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                exchange.response.headers.allow shouldBe setOf(HttpMethod.GET)
                mapper.readTree(exchange.response.bodyAsString.block()).get("message").asString() shouldBe
                    "지원하지 않는 요청 메서드입니다."
            }

            it("HEAD 오류 응답은 본문을 포함하지 않는다") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.head("/api/health"))

                handler.handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()

                exchange.response.statusCode shouldBe HttpStatus.NOT_FOUND
                exchange.response.bodyAsString.block() shouldBe ""
            }
        }
    })

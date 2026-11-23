package com.cowork.project.global.exception

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.resource.NoResourceFoundException

class GlobalExceptionHandlerTest :
    DescribeSpec({
        val handler = GlobalExceptionHandler()

        describe("매핑이 없는 경로 요청은") {
            it("404와 오류 메시지를 반환한다") {
                val exception = NoResourceFoundException(HttpMethod.GET, "/unknown", "unknown")

                val response = handler.handleNoResourceFound(exception)

                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body shouldBe GlobalExceptionHandler.ErrorResponse("요청한 API를 찾을 수 없습니다.")
            }
        }

        describe("지원하지 않는 메서드 요청은") {
            it("405와 허용 메서드를 반환한다") {
                val exception = HttpRequestMethodNotSupportedException("PATCH", listOf("GET", "PUT"))

                val response = handler.handleMethodNotSupported(exception)

                response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.headers.allow shouldContainExactlyInAnyOrder listOf(HttpMethod.GET, HttpMethod.PUT)
                response.body shouldBe GlobalExceptionHandler.ErrorResponse("지원하지 않는 HTTP 메서드입니다.")
            }
        }
    })

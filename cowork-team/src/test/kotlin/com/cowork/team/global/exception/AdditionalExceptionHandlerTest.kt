package com.cowork.team.global.exception

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.resource.NoResourceFoundException

class AdditionalExceptionHandlerTest :
    DescribeSpec({
        val handler = AdditionalExceptionHandler()

        describe("매핑이 없는 경로 요청은") {
            it("404와 공통 오류 응답을 반환한다") {
                val exception = NoResourceFoundException(
                    HttpMethod.PATCH,
                    "/teams/1/members/1/role",
                    "teams/1/members/1/role",
                )

                val response = handler.handleNoResourceFound(exception)

                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body?.status shouldBe HttpStatus.NOT_FOUND
                response.body?.message shouldBe "요청한 API를 찾을 수 없습니다."
            }
        }

        describe("지원하지 않는 메서드 요청은") {
            it("405와 허용 메서드를 반환한다") {
                val exception = HttpRequestMethodNotSupportedException("PATCH", listOf("GET", "PUT"))

                val response = handler.handleHttpRequestMethodNotSupported(exception)

                response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.headers.allow shouldContainExactlyInAnyOrder listOf(HttpMethod.GET, HttpMethod.PUT)
                response.body?.status shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.body?.message shouldBe "지원하지 않는 HTTP 메서드입니다."
            }
        }

        describe("다른 Spring 표준 오류는") {
            it("예외의 상태 코드와 헤더로 응답한다") {
                val exception = HttpMediaTypeNotSupportedException(
                    MediaType.TEXT_PLAIN,
                    listOf(MediaType.APPLICATION_JSON),
                )

                val response = handler.handleInternal(exception)

                response.statusCode shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
                response.headers.accept shouldBe listOf(MediaType.APPLICATION_JSON)
                response.body?.status shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
                response.body?.message shouldBe "요청을 처리할 수 없습니다."
            }
        }

        describe("처리되지 않은 예외는") {
            it("500을 반환한다") {
                val response = handler.handleInternal(IllegalStateException("boom"))

                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.message shouldBe "서버 오류가 발생했습니다."
            }
        }
    })

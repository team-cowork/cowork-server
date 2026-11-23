package com.cowork.project.global.exception

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
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

        describe("다른 Spring 표준 오류는") {
            it("필수 파라미터가 없으면 400을 반환한다") {
                val exception = MissingServletRequestParameterException("from", "Instant")

                val response = handler.handleInternal(exception)

                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body shouldBe GlobalExceptionHandler.ErrorResponse("요청을 처리할 수 없습니다.")
            }

            it("지원하지 않는 Content-Type이면 415와 Accept 헤더를 반환한다") {
                val exception = HttpMediaTypeNotSupportedException(
                    MediaType.TEXT_PLAIN,
                    listOf(MediaType.APPLICATION_JSON),
                )

                val response = handler.handleInternal(exception)

                response.statusCode shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
                response.headers.accept shouldBe listOf(MediaType.APPLICATION_JSON)
                response.body shouldBe GlobalExceptionHandler.ErrorResponse("요청을 처리할 수 없습니다.")
            }
        }

        describe("처리되지 않은 예외는") {
            it("500을 반환한다") {
                val response = handler.handleInternal(IllegalStateException("boom"))

                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body shouldBe GlobalExceptionHandler.ErrorResponse("서버 오류가 발생했습니다.")
            }
        }
    })

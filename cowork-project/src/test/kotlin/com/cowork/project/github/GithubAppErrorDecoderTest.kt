package com.cowork.project.github

import feign.Request
import feign.Response
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets

class GithubAppErrorDecoderTest : DescribeSpec({

    val objectMapper = jacksonObjectMapper()
    val decoder = GithubAppErrorDecoder(objectMapper)

    fun request(): Request =
        Request.create(
            Request.HttpMethod.GET,
            "/api/repos/my-org/my-repo/pulls/5",
            emptyMap(),
            null,
            StandardCharsets.UTF_8,
        )

    fun response(status: Int, body: String? = null): Response {
        val builder = Response.builder()
            .status(status)
            .reason("error")
            .headers(emptyMap())
            .request(request())
        if (body != null) {
            builder.body(body, StandardCharsets.UTF_8)
        }
        return builder.build()
    }

    describe("GithubAppErrorDecoder 클래스의") {
        describe("decode 메서드는") {
            context("응답 상태코드가 401인 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    val ex = decoder.decode("GithubAppClient#getPullRequest", response(401)) as ExpectedException

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                    ex.message shouldBe "GitHub 연동 서버 설정에 문제가 있습니다."
                }
            }

            context("응답 상태코드가 4xx이고 message 필드를 포함하는 경우") {
                it("응답 상태코드와 message를 그대로 전달한다") {
                    val body = """{"message":"PR을 찾을 수 없습니다."}"""
                    val ex = decoder.decode("GithubAppClient#getPullRequest", response(404, body)) as ExpectedException

                    ex.statusCode shouldBe HttpStatus.NOT_FOUND
                    ex.message shouldBe "PR을 찾을 수 없습니다."
                }
            }

            context("응답 상태코드가 4xx이고 응답 본문이 없는 경우") {
                it("기본 오류 메시지로 대체한다") {
                    val ex = decoder.decode("GithubAppClient#getPullRequest", response(409)) as ExpectedException

                    ex.statusCode shouldBe HttpStatus.CONFLICT
                    ex.message shouldBe "GitHub 연동 서버 오류"
                }
            }

            context("응답 상태코드가 5xx인 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    val ex = decoder.decode("GithubAppClient#getPullRequest", response(503)) as ExpectedException

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                    ex.message shouldBe "GitHub 연동 서버와 통신할 수 없습니다."
                }
            }
        }
    }
})

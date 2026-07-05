package com.cowork.project.domain.github.service

import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubAppCallExecutorTest : DescribeSpec({

    val executor = GithubAppCallExecutor()

    describe("GithubAppCallExecutor 클래스의") {
        describe("execute 메서드는") {
            context("call이 정상적으로 완료되는 경우") {
                it("결과를 그대로 반환한다") {
                    val result = executor.execute { "ok" }

                    result shouldBe "ok"
                }
            }

            context("call 실행 중 FeignException이 발생하는 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    val networkError = FeignException.NotFound(
                        "connect timed out",
                        Request.create(Request.HttpMethod.GET, "/api/repos/my-org/my-repo/pulls", emptyMap(), null, RequestTemplate()),
                        null,
                        emptyMap(),
                    )

                    val ex = shouldThrow<ExpectedException> { executor.execute { throw networkError } }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }
        }
    }
})

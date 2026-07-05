package com.cowork.project.domain.github.service

import com.cowork.project.global.client.UserClient
import com.cowork.project.global.client.UserProfileResDto
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubUsernameResolverTest : DescribeSpec({

    lateinit var userClient: UserClient
    lateinit var resolver: GithubUsernameResolver

    beforeEach {
        userClient = mockk()
        resolver = GithubUsernameResolver(userClient)
    }

    describe("GithubUsernameResolver 클래스의") {
        describe("resolve 메서드는") {
            context("githubId가 연동되어 있는 경우") {
                it("githubId를 반환한다") {
                    every { userClient.getUserProfile(7L) } returns UserProfileResDto(githubId = "octocat")

                    val username = resolver.resolve(7L)

                    username shouldBe "octocat"
                }
            }

            context("githubId가 null인 경우") {
                it("BAD_REQUEST를 던진다") {
                    every { userClient.getUserProfile(7L) } returns UserProfileResDto(githubId = null)

                    val ex = shouldThrow<ExpectedException> { resolver.resolve(7L) }

                    ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("cowork-user가 404를 반환하는 경우") {
                it("BAD_REQUEST로 변환한다") {
                    val notFound = FeignException.NotFound(
                        "not found",
                        Request.create(Request.HttpMethod.GET, "/users/7", emptyMap(), null, RequestTemplate()),
                        null,
                        emptyMap(),
                    )
                    every { userClient.getUserProfile(7L) } throws notFound

                    val ex = shouldThrow<ExpectedException> { resolver.resolve(7L) }

                    ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("cowork-user 호출 중 예기치 못한 예외가 발생하는 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    every { userClient.getUserProfile(7L) } throws IllegalStateException("No instances available for cowork-user")

                    val ex = shouldThrow<ExpectedException> { resolver.resolve(7L) }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }
        }
    }
})

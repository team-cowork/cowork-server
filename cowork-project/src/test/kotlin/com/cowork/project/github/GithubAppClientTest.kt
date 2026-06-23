package com.cowork.project.github

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import team.themoment.sdk.exception.ExpectedException

class GithubAppClientTest : DescribeSpec({

    lateinit var server: MockRestServiceServer
    lateinit var client: GithubAppClient

    beforeEach {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = GithubAppClient("http://localhost", builder, "test-key")
    }

    describe("GithubAppClient 클래스의") {
        describe("getPullRequest 메서드는") {
            context("정상 응답인 경우") {
                it("내부 API 키 헤더를 포함해 PR 상세를 반환한다") {
                    server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
                        .andExpect(header("X-Internal-Api-Key", "test-key"))
                        .andRespond(
                            withSuccess(
                                """{"number":1,"title":"t","body":null,"author":"a","state":"open",
                                   |"mergeable":true,"mergeableState":"clean","reviewDecision":null,
                                   |"headRef":"h","baseRef":"b","htmlUrl":"u"}""".trimMargin(),
                                MediaType.APPLICATION_JSON,
                            )
                        )

                    val result = client.getPullRequest("o", "r", 1)

                    result.title shouldBe "t"
                    result.author shouldBe "a"
                }
            }

            context("401 응답인 경우") {
                it("502 + 설정오류 메시지로 변환한다") {
                    server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
                        .andRespond(
                            withStatus(HttpStatus.UNAUTHORIZED)
                                .body("""{"message":"Invalid internal API key"}""")
                                .contentType(MediaType.APPLICATION_JSON)
                        )

                    val ex = shouldThrow<ExpectedException> { client.getPullRequest("o", "r", 1) }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }

            context("404 응답인 경우") {
                it("상태코드와 메시지를 그대로 전달한다") {
                    server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
                        .andRespond(
                            withStatus(HttpStatus.NOT_FOUND)
                                .body("""{"message":"PR을 찾을 수 없습니다"}""")
                                .contentType(MediaType.APPLICATION_JSON)
                        )

                    val ex = shouldThrow<ExpectedException> { client.getPullRequest("o", "r", 1) }

                    ex.statusCode shouldBe HttpStatus.NOT_FOUND
                    ex.message shouldBe "PR을 찾을 수 없습니다"
                }
            }

            context("5xx 응답인 경우") {
                it("502로 변환한다") {
                    server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
                        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

                    val ex = shouldThrow<ExpectedException> { client.getPullRequest("o", "r", 1) }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }
        }

        describe("mergePullRequest 메서드는") {
            context("409 응답인 경우") {
                it("상태코드와 메시지를 그대로 전달한다") {
                    server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1/merge"))
                        .andRespond(
                            withStatus(HttpStatus.CONFLICT)
                                .body("""{"message":"머지할 수 없는 상태입니다 (mergeable_state: dirty)"}""")
                                .contentType(MediaType.APPLICATION_JSON)
                        )

                    val ex = shouldThrow<ExpectedException> { client.mergePullRequest("o", "r", 1, "octocat") }

                    ex.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }
    }
})

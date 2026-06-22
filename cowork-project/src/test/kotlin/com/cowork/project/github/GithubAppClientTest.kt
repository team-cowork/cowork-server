package com.cowork.project.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import team.themoment.sdk.exception.ExpectedException

class GithubAppClientTest {

    private lateinit var server: MockRestServiceServer
    private lateinit var client: GithubAppClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = GithubAppClient("http://localhost", builder, "test-key")
    }

    @Test
    fun `getPullRequest는 내부 API 키 헤더를 포함해 정상 응답을 반환`() {
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

        assertEquals("t", result.title)
        assertEquals("a", result.author)
    }

    @Test
    fun `401 응답은 502 + 설정오류 메시지로 변환`() {
        server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .body("""{"message":"Invalid internal API key"}""")
                    .contentType(MediaType.APPLICATION_JSON)
            )

        val ex = assertThrows(ExpectedException::class.java) { client.getPullRequest("o", "r", 1) }

        assertEquals(HttpStatus.BAD_GATEWAY, ex.statusCode)
    }

    @Test
    fun `404 응답은 그대로 전달`() {
        server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .body("""{"message":"PR을 찾을 수 없습니다"}""")
                    .contentType(MediaType.APPLICATION_JSON)
            )

        val ex = assertThrows(ExpectedException::class.java) { client.getPullRequest("o", "r", 1) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        assertEquals("PR을 찾을 수 없습니다", ex.message)
    }

    @Test
    fun `409 응답은 그대로 전달`() {
        server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1/merge"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .body("""{"message":"머지할 수 없는 상태입니다 (mergeable_state: dirty)"}""")
                    .contentType(MediaType.APPLICATION_JSON)
            )

        val ex = assertThrows(ExpectedException::class.java) { client.mergePullRequest("o", "r", 1, "octocat") }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `5xx 응답은 502로 변환`() {
        server.expect(requestTo("http://localhost/api/repos/o/r/pulls/1"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val ex = assertThrows(ExpectedException::class.java) { client.getPullRequest("o", "r", 1) }

        assertEquals(HttpStatus.BAD_GATEWAY, ex.statusCode)
    }
}

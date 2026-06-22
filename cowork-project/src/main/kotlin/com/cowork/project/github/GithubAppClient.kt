package com.cowork.project.github

import com.cowork.project.dto.GithubApproveResultResponse
import com.cowork.project.dto.GithubMergeResultResponse
import com.cowork.project.dto.GithubPullRequestFileResponse
import com.cowork.project.dto.GithubPullRequestResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import team.themoment.sdk.exception.ExpectedException

private const val INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"

/**
 * `cowork-github-app`의 PR 조회/머지/승인 HTTP API를 호출하는 클라이언트.
 *
 * `cowork-github-app`은 이 모놀레포 밖의 별도 저장소라 Eureka에 등록되어 있지 않으므로,
 * 고정 URL(`github-app.service-url`) + `RestClient`로 호출한다.
 */
@Component
class GithubAppClient(
    @Value("\${github-app.service-url}") baseUrl: String,
    restClientBuilder: RestClient.Builder,
    @Value("\${github-app.internal-api-key}") private val internalApiKey: String,
) {
    private val logger = LoggerFactory.getLogger(GithubAppClient::class.java)

    private val restClient = restClientBuilder
        .baseUrl(baseUrl)
        .defaultHeader(INTERNAL_API_KEY_HEADER, internalApiKey)
        .build()

    fun getPullRequest(owner: String, repo: String, prNumber: Int): GithubPullRequestResponse =
        execute {
            restClient.get()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .retrieve()
                .body(GithubPullRequestResponse::class.java)!!
        }

    fun listPullRequestFiles(owner: String, repo: String, prNumber: Int): List<GithubPullRequestFileResponse> =
        execute {
            restClient.get()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/files", owner, repo, prNumber)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<GithubPullRequestFileResponse>>() {})!!
        }

    fun mergePullRequest(owner: String, repo: String, prNumber: Int, requesterGithubUsername: String): GithubMergeResultResponse =
        execute {
            restClient.post()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/merge", owner, repo, prNumber)
                .body(mapOf("requesterGithubUsername" to requesterGithubUsername))
                .retrieve()
                .body(GithubMergeResultResponse::class.java)!!
        }

    fun approvePullRequest(owner: String, repo: String, prNumber: Int, requesterGithubUsername: String): GithubApproveResultResponse =
        execute {
            restClient.post()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/approve", owner, repo, prNumber)
                .body(mapOf("requesterGithubUsername" to requesterGithubUsername))
                .retrieve()
                .body(GithubApproveResultResponse::class.java)!!
        }

    private fun <T> execute(call: () -> T): T =
        try {
            call()
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 401) {
                logger.error("cowork-github-app 내부 API 키 인증 실패", e)
                throw ExpectedException("GitHub 연동 서버 설정에 문제가 있습니다.", HttpStatus.BAD_GATEWAY)
            }
            if (e.statusCode.is4xxClientError) {
                throw ExpectedException(extractMessage(e), HttpStatus.valueOf(e.statusCode.value()))
            }
            logger.error("cowork-github-app 호출 실패 (5xx)", e)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        } catch (e: RestClientException) {
            logger.error("cowork-github-app 호출 실패 (네트워크/타임아웃)", e)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        }

    private fun extractMessage(e: RestClientResponseException): String =
        try {
            @Suppress("UNCHECKED_CAST")
            val body = e.getResponseBodyAs(Map::class.java) as Map<String, Any?>?
            (body?.get("message") as? String) ?: e.message ?: "GitHub 연동 서버 오류"
        } catch (parseError: Exception) {
            e.message ?: "GitHub 연동 서버 오류"
        }
}

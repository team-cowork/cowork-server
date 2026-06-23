package com.cowork.project.github

import com.cowork.project.dto.GithubApproveResultResDto
import com.cowork.project.dto.GithubMergeResultResDto
import com.cowork.project.dto.GithubPullRequestFileResDto
import com.cowork.project.dto.GithubPullRequestResDto
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

    fun getPullRequest(owner: String, repo: String, prNumber: Int): GithubPullRequestResDto =
        execute {
            restClient.get()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .retrieve()
                .body(GithubPullRequestResDto::class.java)!!
        }

    fun listPullRequestFiles(owner: String, repo: String, prNumber: Int): List<GithubPullRequestFileResDto> =
        execute {
            restClient.get()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/files", owner, repo, prNumber)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<GithubPullRequestFileResDto>>() {})!!
        }

    fun mergePullRequest(owner: String, repo: String, prNumber: Int, requesterGithubUsername: String): GithubMergeResultResDto =
        execute {
            restClient.post()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/merge", owner, repo, prNumber)
                .body(mapOf("requesterGithubUsername" to requesterGithubUsername))
                .retrieve()
                .body(GithubMergeResultResDto::class.java)!!
        }

    fun approvePullRequest(owner: String, repo: String, prNumber: Int, requesterGithubUsername: String): GithubApproveResultResDto =
        execute {
            restClient.post()
                .uri("/api/repos/{owner}/{repo}/pulls/{number}/approve", owner, repo, prNumber)
                .body(mapOf("requesterGithubUsername" to requesterGithubUsername))
                .retrieve()
                .body(GithubApproveResultResDto::class.java)!!
        }

    private fun <T> execute(call: () -> T): T =
        try {
            call()
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 401) {
                logger.error("Failed to authenticate with cowork-github-app due to invalid internal API key", e)
                throw ExpectedException("GitHub 연동 서버 설정에 문제가 있습니다.", HttpStatus.BAD_GATEWAY)
            }
            if (e.statusCode.is4xxClientError) {
                throw ExpectedException(extractMessage(e), HttpStatus.valueOf(e.statusCode.value()))
            }
            logger.error("Failed to call cowork-github-app with 5xx server error", e)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        } catch (e: RestClientException) {
            logger.error("Failed to call cowork-github-app due to network or timeout error", e)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        }

    private fun extractMessage(e: RestClientResponseException): String =
        runCatching { e.getResponseBodyAs(Map::class.java)?.get("message") as? String }
            .onFailure { logger.warn("Failed to parse cowork-github-app error response body", it) }
            .getOrNull()
            ?: e.message
            ?: "GitHub 연동 서버 오류"
}

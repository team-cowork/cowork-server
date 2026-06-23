package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.service.GithubPullRequestService

import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub PR", description = "프로젝트에 연결된 GitHub 레포지토리의 PR 조회/머지/승인 API")
@RestController
@RequestMapping("/projects/{projectId}/github/pulls/{prNumber}")
class GithubPullRequestController(
    private val githubPullRequestService: GithubPullRequestService,
) {

    @Operation(summary = "PR 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping
    fun getDetail(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable prNumber: Int,
    ): GithubPullRequestResDto =
        githubPullRequestService.getPullRequestDetail(userId, projectId, prNumber)

    @Operation(summary = "PR 파일 변경 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping("/files")
    fun getFiles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable prNumber: Int,
    ): List<GithubPullRequestFileResDto> =
        githubPullRequestService.listPullRequestFiles(userId, projectId, prNumber)

    @Operation(summary = "PR 머지 (squash)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "머지 성공 (이미 머지된 경우 alreadyMerged=true로 멱등 응답)"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음 또는 GitHub 레포 쓰기 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "409", description = "머지 불가 상태 (충돌/체크 실패 등)"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PostMapping("/merge")
    fun merge(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable prNumber: Int,
    ): GithubMergeResultResDto =
        githubPullRequestService.mergePullRequest(userId, projectId, prNumber)

    @Operation(summary = "PR 승인", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "승인 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음 또는 GitHub 계정 미연동"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음, GitHub 레포 쓰기 권한 없음, 또는 본인 PR 승인 시도"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 PR 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PostMapping("/approve")
    fun approve(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable prNumber: Int,
    ): GithubApproveResultResDto =
        githubPullRequestService.approvePullRequest(userId, projectId, prNumber)
}

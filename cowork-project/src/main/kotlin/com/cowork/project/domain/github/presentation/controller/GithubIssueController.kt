package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.UpdateGithubIssueLabelsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub 이슈", description = "프로젝트에 연결된 GitHub 레포지토리의 이슈 조회/생성 API")
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/issues/{issueNumber}")
class GithubIssueController(
    private val updateGithubIssueLabelsService: UpdateGithubIssueLabelsService,
) {

    @Operation(
        summary = "이슈 라벨 적용/변경",
        description = "이슈의 라벨을 전체 교체한다. 라벨 자동 적용 정책이 꺼져있을 때 사람이 직접 라벨을 고르는 용도.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "적용 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PatchMapping("/labels")
    fun updateLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: UpdateGithubIssueLabelsReqDto,
    ): GithubIssueResDto = updateGithubIssueLabelsService.execute(userId, projectId, repoId, issueNumber, request)
}

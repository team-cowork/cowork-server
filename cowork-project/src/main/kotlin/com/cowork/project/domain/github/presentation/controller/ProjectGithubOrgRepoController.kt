package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.service.QueryProjectGithubReposService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub 레포지토리", description = "팀이 연결한 GitHub 조직의 레포지토리 목록 조회 API")
@RestController
@RequestMapping("/projects/{projectId}/github/repos")
class ProjectGithubOrgRepoController(private val queryProjectGithubReposService: QueryProjectGithubReposService) {

    @Operation(
        summary = "팀 GitHub 조직 레포지토리 목록 조회",
        description = "프로젝트가 속한 팀이 연결한 GitHub 조직의 레포지토리 목록을 반환한다. 레포 연결 팝업에서 사용된다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "팀이 GitHub 조직에 연결되어 있지 않음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping
    fun getRepos(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): List<GithubRepoSummaryResDto> = queryProjectGithubReposService.execute(userId, projectId)
}

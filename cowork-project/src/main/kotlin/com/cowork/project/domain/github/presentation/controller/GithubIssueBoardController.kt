package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.service.CreateGithubIssueService
import com.cowork.project.domain.github.service.ListGithubLabelsService
import com.cowork.project.domain.github.service.QueryIssueBoardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub 이슈", description = "프로젝트에 연결된 GitHub 레포지토리의 이슈 조회/생성 API")
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/issues")
class GithubIssueBoardController(
    private val queryIssueBoardService: QueryIssueBoardService,
    private val createGithubIssueService: CreateGithubIssueService,
    private val listGithubLabelsService: ListGithubLabelsService,
) {

    @Operation(
        summary = "열린 이슈 목록 조회",
        description = "연결된 레포의 열린(open) 이슈를 라벨과 함께 반환한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping
    fun getIssues(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): List<GithubIssueResDto> = queryIssueBoardService.execute(userId, projectId, repoId)

    @Operation(
        summary = "이슈 생성",
        description = "연결된 레포에 이슈를 생성한다. label을 지정하면 생성과 동시에 적용한다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "프로젝트 수정 권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createIssue(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @RequestBody request: CreateGithubIssueReqDto,
    ): GithubIssueResDto = createGithubIssueService.execute(userId, projectId, repoId, request)

    @Operation(
        summary = "레포에 정의된 라벨 목록 조회",
        description = "라벨 선택 UI(피커)에 쓰인다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "연결된 GitHub 레포지토리 없음"),
        ApiResponse(responseCode = "403", description = "팀 멤버 아님"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
        ApiResponse(responseCode = "502", description = "GitHub 연동 서버 통신 오류"),
    )
    @GetMapping("/labels")
    fun getLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): List<GithubLabelResDto> = listGithubLabelsService.execute(userId, projectId, repoId)
}

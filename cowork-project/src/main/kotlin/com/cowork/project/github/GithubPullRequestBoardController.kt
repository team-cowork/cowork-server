package com.cowork.project.github

import com.cowork.project.dto.GithubPullRequestBoardResDto
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

@Tag(name = "GitHub PR", description = "프로젝트에 연결된 GitHub 레포지토리의 PR 조회/머지/승인 API")
@RestController
@RequestMapping("/projects/{projectId}/github/pulls")
class GithubPullRequestBoardController(
    private val githubPullRequestService: GithubPullRequestService,
) {

    @Operation(
        summary = "진행 중 PR 보드 조회",
        description = "연결된 레포의 열린 PR을 Draft / 리뷰중 컬럼으로 그룹핑해 반환한다.",
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
    fun getBoard(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): GithubPullRequestBoardResDto =
        githubPullRequestService.getPullRequestBoard(userId, projectId)
}

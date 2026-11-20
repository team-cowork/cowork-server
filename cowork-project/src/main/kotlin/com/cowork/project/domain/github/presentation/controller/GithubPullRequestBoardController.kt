package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto
import com.cowork.project.domain.github.service.QueryPullRequestBoardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "GitHub PR", description = "프로젝트에 연결된 GitHub 저장소의 PR 조회/머지/승인 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "프로젝트 생성이나 권한 변경 직후 권한 정보가 아직 동기화되지 않음.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/pulls")
class GithubPullRequestBoardController(private val queryPullRequestBoardService: QueryPullRequestBoardService) {

    @Operation(
        summary = "열린 PR 목록 조회",
        description = "열린 PR을 초안과 리뷰 중인 PR로 나누어 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 등록된 저장소 없음",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
    )
    @GetMapping
    fun getBoard(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): GithubPullRequestBoardResDto = queryPullRequestBoardService.execute(userId, projectId, repoId)
}

package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.service.QueryProjectGithubReposService
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

@Tag(name = "GitHub 저장소", description = "팀이 연결한 GitHub 조직의 저장소 목록 조회 API")
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
@RequestMapping("/projects/{projectId}/github/repos")
class ProjectGithubOrgRepoController(private val queryProjectGithubReposService: QueryProjectGithubReposService) {

    @Operation(
        summary = "팀 GitHub 조직의 저장소 목록 조회",
        description = "프로젝트가 속한 팀에 연결된 GitHub 조직의 저장소를 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "팀이 GitHub 조직에 연결되어 있지 않음",
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
            description = "프로젝트 없음",
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
    fun getRepos(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): List<GithubRepoSummaryResDto> = queryProjectGithubReposService.execute(userId, projectId)
}

package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.service.CreateGithubIssueService
import com.cowork.project.domain.github.service.ListGithubLabelsService
import com.cowork.project.domain.github.service.QueryIssueBoardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
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
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "GitHub 이슈", description = "프로젝트에 연결된 GitHub 저장소의 이슈 조회/생성 API")
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
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/issues")
class GithubIssueBoardController(
    private val queryIssueBoardService: QueryIssueBoardService,
    private val createGithubIssueService: CreateGithubIssueService,
    private val listGithubLabelsService: ListGithubLabelsService,
) {

    @Operation(
        summary = "열린 이슈 목록 조회",
        description = "연결된 저장소의 열린 이슈와 각 이슈의 라벨을 반환합니다.",
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
    fun getIssues(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): List<GithubIssueResDto> = queryIssueBoardService.execute(userId, projectId, repoId)

    @Operation(
        summary = "GitHub 이슈 생성 요청",
        description = "label을 지정하면 새 이슈에 해당 라벨을 적용합니다. 응답은 요청 접수만 확인하며 이슈 번호와 URL은 포함하지 않습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class)),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 수정 권한 없음",
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
    )
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createIssue(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @RequestBody request: CreateGithubIssueReqDto,
    ) = createGithubIssueService.execute(userId, projectId, repoId, request)

    @Operation(
        summary = "저장소 라벨 목록 조회",
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
    @GetMapping("/labels")
    fun getLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
    ): List<GithubLabelResDto> = listGithubLabelsService.execute(userId, projectId, repoId)
}

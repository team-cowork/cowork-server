package com.cowork.project.domain.githubBoard.presentation.controller

import com.cowork.project.domain.githubBoard.presentation.data.request.AddProjectGithubBoardReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.ProjectGithubBoardResDto
import com.cowork.project.domain.githubBoard.service.AddProjectGithubBoardService
import com.cowork.project.domain.githubBoard.service.QueryProjectGithubBoardsService
import com.cowork.project.domain.githubBoard.service.RemoveProjectGithubBoardService
import com.cowork.project.domain.githubBoard.service.RequestGithubBoardSyncService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "GitHub 보드", description = "프로젝트에 연결한 GitHub Project(v2) 보드 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
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
@RequestMapping("/projects/{projectId}/github-boards")
class ProjectGithubBoardController(
    private val addProjectGithubBoardService: AddProjectGithubBoardService,
    private val queryProjectGithubBoardsService: QueryProjectGithubBoardsService,
    private val removeProjectGithubBoardService: RemoveProjectGithubBoardService,
    private val requestGithubBoardSyncService: RequestGithubBoardSyncService,
) {

    @Operation(summary = "GitHub Project 연결", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "연결 성공. 첫 동기화 전에는 syncStatus가 SYNCING"),
        ApiResponse(
            responseCode = "400",
            description = "유효하지 않은 URL 또는 개인 계정 소유 Project",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 관리 권한 없음 또는 팀 조직이 아닌 Project",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 연결된 Project",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun addBoard(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: AddProjectGithubBoardReqDto,
    ): ProjectGithubBoardResDto = addProjectGithubBoardService.execute(userId, projectId, request)

    @Operation(summary = "연결한 GitHub Project 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 보드 보기 권한 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping
    fun getBoards(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): List<ProjectGithubBoardResDto> = queryProjectGithubBoardsService.execute(userId, projectId)

    @Operation(summary = "GitHub Project 연결 해제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 관리 권한 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 연결 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @DeleteMapping("/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeBoard(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
    ) = removeProjectGithubBoardService.execute(userId, projectId, boardId)

    @Operation(
        summary = "GitHub Project 재동기화 요청",
        description = "GitHub가 웹훅으로 알리지 않는 field와 view 설정 변경을 반영합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "요청 접수. 직전 요청 후 1분 안의 요청은 새 동기화를 시작하지 않음",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 보드 수정 권한 없음 또는 팀 조직이 아닌 Project",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 연결 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/{boardId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestSync(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
    ) = requestGithubBoardSyncService.execute(userId, projectId, boardId)
}

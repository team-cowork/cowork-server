package com.cowork.project.domain.githubBoard.presentation.controller

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemsResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardKanbanResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardItemsService
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardKanbanService
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardViewsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.response.CommonApiResponse

private val FIELD_PARAM = Regex("^field\\[(.+)]$")
private val ITEM_TYPES = setOf("ISSUE", "PULL_REQUEST", "DRAFT_ISSUE")

@Tag(name = "GitHub 보드", description = "프로젝트에 연결한 GitHub Project(v2) 보드 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "403",
        description = "GitHub 보드 보기 권한 없음 또는 팀 조직이 아닌 Project",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "프로젝트, 연결, 뷰 없음 또는 GitHub에서 Project를 찾을 수 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "409",
        description = "GitHub App에 Projects 권한이 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "GitHub Project 동기화가 끝나지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/github-boards/{boardId}/views")
class GithubBoardViewController(
    private val queryGithubBoardViewsService: QueryGithubBoardViewsService,
    private val queryGithubBoardKanbanService: QueryGithubBoardKanbanService,
    private val queryGithubBoardItemsService: QueryGithubBoardItemsService,
) {

    @Operation(summary = "GitHub Project 뷰 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping
    fun getViews(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
    ): List<GithubBoardViewResDto> = queryGithubBoardViewsService.execute(userId, projectId, boardId)

    @Operation(summary = "칸반 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "Board 뷰가 아님, 없는 field 조건 또는 잘못된 type",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping("/{viewNumber}/kanban")
    fun getKanban(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable viewNumber: Int,
        @Parameter(description = "담당자 GitHub 사용자 이름. 쉼표로 구분하며 me는 요청자", example = "me")
        @RequestParam(required = false) assignee: String?,
        @Parameter(description = "저장소. owner/repo 또는 repo 이름", example = "my-org/app")
        @RequestParam(required = false) repo: String?,
        @Parameter(description = "item 종류", schema = Schema(allowableValues = ["ISSUE", "PULL_REQUEST", "DRAFT_ISSUE"]))
        @RequestParam(required = false) type: String?,
        @Parameter(description = "제목 부분 일치 검색어", example = "로그인")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "true면 뷰 filter 미적용", example = "false")
        @RequestParam(defaultValue = "false") ignoreViewFilter: Boolean,
        @Parameter(
            name = "field[이름]",
            `in` = ParameterIn.QUERY,
            description = "Project field 조건. 쉼표로 구분한 값 중 하나와 일치",
            example = "field[Status]=Todo,In Progress",
        )
        @RequestParam params: MultiValueMap<String, String>,
    ): GithubBoardKanbanResDto = queryGithubBoardKanbanService.execute(
        userId,
        projectId,
        boardId,
        viewNumber,
        toQuery(assignee, repo, type, q, ignoreViewFilter, params),
    )

    @Operation(summary = "뷰 item 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "없는 field 조건 또는 잘못된 type",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping("/{viewNumber}/items")
    fun getItems(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable viewNumber: Int,
        @Parameter(description = "담당자 GitHub 사용자 이름. 쉼표로 구분하며 me는 요청자", example = "me")
        @RequestParam(required = false) assignee: String?,
        @Parameter(description = "저장소. owner/repo 또는 repo 이름", example = "my-org/app")
        @RequestParam(required = false) repo: String?,
        @Parameter(description = "item 종류", schema = Schema(allowableValues = ["ISSUE", "PULL_REQUEST", "DRAFT_ISSUE"]))
        @RequestParam(required = false) type: String?,
        @Parameter(description = "제목 부분 일치 검색어", example = "로그인")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "true면 뷰 filter 미적용", example = "false")
        @RequestParam(defaultValue = "false") ignoreViewFilter: Boolean,
        @Parameter(
            name = "field[이름]",
            `in` = ParameterIn.QUERY,
            description = "Project field 조건. 쉼표로 구분한 값 중 하나와 일치",
            example = "field[Status]=Todo,In Progress",
        )
        @RequestParam params: MultiValueMap<String, String>,
    ): GithubBoardItemsResDto = queryGithubBoardItemsService.execute(
        userId,
        projectId,
        boardId,
        viewNumber,
        toQuery(assignee, repo, type, q, ignoreViewFilter, params),
    )

    private fun toQuery(
        assignee: String?,
        repo: String?,
        type: String?,
        q: String?,
        ignoreViewFilter: Boolean,
        params: MultiValueMap<String, String>,
    ): GithubBoardItemQuery {
        val normalizedType = type?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (normalizedType != null && normalizedType !in ITEM_TYPES) {
            throw ExpectedException("type은 ISSUE, PULL_REQUEST, DRAFT_ISSUE 중 하나여야 합니다.", HttpStatus.BAD_REQUEST)
        }
        val fields = params.entries
            .mapNotNull { (key, values) ->
                val name = FIELD_PARAM.matchEntire(key)?.groupValues?.get(1) ?: return@mapNotNull null
                name to values.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
        return GithubBoardItemQuery(
            assignees = assignee.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            fields = fields,
            repo = repo?.trim()?.takeIf { it.isNotEmpty() },
            type = normalizedType,
            q = q?.trim()?.takeIf { it.isNotEmpty() },
            ignoreViewFilter = ignoreViewFilter,
        )
    }
}

package com.cowork.project.domain.githubBoard.presentation.controller

import com.cowork.project.domain.githubBoard.presentation.data.request.MoveGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.request.ReorderGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationResDto
import com.cowork.project.domain.githubBoard.service.MoveGithubBoardItemService
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardOperationService
import com.cowork.project.domain.githubBoard.service.ReorderGithubBoardItemService
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

@Tag(name = "GitHub 보드", description = "프로젝트에 연결한 GitHub Project(v2) 보드 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "프로젝트, 연결, 뷰, 카드 또는 작업 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "409",
        description = "GitHub App에 Projects 권한 없음 또는 정렬이 있는 뷰에서 순서 변경",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "GitHub Project 동기화가 끝나지 않았거나, 프로젝트 생성이나 권한 변경 직후 권한 정보가 아직 동기화되지 않음. 잠시 후 다시 시도할 수 있습니다.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/github-boards/{boardId}")
class GithubBoardItemController(
    private val moveGithubBoardItemService: MoveGithubBoardItemService,
    private val reorderGithubBoardItemService: ReorderGithubBoardItemService,
    private val queryGithubBoardOperationService: QueryGithubBoardOperationService,
) {

    @Operation(summary = "카드 컬럼 이동 요청", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "요청 접수. 결과는 작업 조회 API로 확인. 정렬이 있는 뷰는 컬럼 값만 변경",
        ),
        ApiResponse(
            responseCode = "400",
            description = "Board 뷰가 아니거나 없는 컬럼, 또는 기준 카드가 대상 컬럼에 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 보드 수정 권한 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/items/{itemId}/move")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun moveItem(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @RequestBody request: MoveGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto =
        moveGithubBoardItemService.execute(userId, projectId, boardId, itemId, request)

    @Operation(summary = "카드 순서 변경 요청", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수. 결과는 작업 조회 API로 확인"),
        ApiResponse(
            responseCode = "400",
            description = "Board 뷰가 아님, 기준 카드가 같은 컬럼에 없음 또는 컬럼에 다른 카드 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 보드 수정 권한 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/items/{itemId}/reorder")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reorderItem(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable itemId: String,
        @RequestBody request: ReorderGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto =
        reorderGithubBoardItemService.execute(userId, projectId, boardId, itemId, request)

    @Operation(summary = "카드 이동 작업 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "GitHub 보드 보기 권한 없음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping("/operations/{operationId}")
    fun getOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable boardId: Long,
        @PathVariable operationId: String,
    ): GithubBoardOperationResDto = queryGithubBoardOperationService.execute(userId, projectId, boardId, operationId)
}

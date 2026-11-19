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
import io.swagger.v3.oas.annotations.links.Link
import io.swagger.v3.oas.annotations.links.LinkParameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
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

@Tag(name = "GitHub 보드")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "인증이 필요합니다.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "요청한 리소스를 찾을 수 없습니다.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "409",
        description = "GitHub App의 Projects 권한이 없거나 뷰 정렬로 순서 변경이 제한됩니다.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "GitHub 데이터 동기화가 완료되지 않았습니다.",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "요청을 처리하지 못했습니다.",
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

    @Operation(
        summary = "카드 컬럼 이동",
        description = "정렬을 설정한 뷰에서는 컬럼 값만 변경합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "작업을 접수했습니다.",
            content = [
                Content(
                    schema = Schema(allOf = [CommonApiResponse::class]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = GithubBoardOperationAcceptedResDto::class),
                        ),
                    ],
                ),
            ],
            links = [
                Link(
                    name = "작업 결과 조회",
                    operationId = "getGithubBoardOperation",
                    parameters = [
                        LinkParameter(name = "projectId", expression = "\$request.path.projectId"),
                        LinkParameter(name = "boardId", expression = "\$request.path.boardId"),
                        LinkParameter(name = "operationId", expression = "\$response.body#/data/operationId"),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "보드 뷰가 아니거나 대상 컬럼 또는 기준 카드가 올바르지 않습니다.",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "보드 수정 권한이 없습니다.",
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

    @Operation(summary = "카드 순서 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "작업을 접수했습니다.",
            content = [
                Content(
                    schema = Schema(allOf = [CommonApiResponse::class]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = GithubBoardOperationAcceptedResDto::class),
                        ),
                    ],
                ),
            ],
            links = [
                Link(
                    name = "작업 결과 조회",
                    operationId = "getGithubBoardOperation",
                    parameters = [
                        LinkParameter(name = "projectId", expression = "\$request.path.projectId"),
                        LinkParameter(name = "boardId", expression = "\$request.path.boardId"),
                        LinkParameter(name = "operationId", expression = "\$response.body#/data/operationId"),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "보드 뷰가 아니거나 같은 컬럼에서 순서를 변경할 수 없습니다.",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "보드 수정 권한이 없습니다.",
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

    @Operation(
        summary = "카드 쓰기 작업 조회",
        operationId = "getGithubBoardOperation",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "작업을 조회했습니다.",
            content = [
                Content(
                    schema = Schema(allOf = [CommonApiResponse::class]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = GithubBoardOperationResDto::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "보드 조회 권한이 없습니다.",
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

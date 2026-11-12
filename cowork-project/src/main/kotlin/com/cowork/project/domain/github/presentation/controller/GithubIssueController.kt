package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.ListGithubCommentsService
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import com.cowork.project.domain.github.service.UpdateGithubIssueLabelsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
        content = [
            Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """{"status":"401 UNAUTHORIZED","code":401,"message":"Unauthorized"}""",
                    ),
                ],
            ),
        ],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/github-repos/{repoId}/issues/{issueNumber}")
class GithubIssueController(
    private val updateGithubIssueLabelsService: UpdateGithubIssueLabelsService,
    private val listGithubCommentsService: ListGithubCommentsService,
    private val createGithubCommentService: CreateGithubCommentService,
    private val updateGithubCommentService: UpdateGithubCommentService,
    private val deleteGithubCommentService: DeleteGithubCommentService,
) {

    @Operation(
        summary = "이슈 라벨 교체 요청",
        description = "이슈의 기존 라벨을 요청한 목록으로 교체합니다. 응답은 요청 접수만 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 수정 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 등록된 저장소 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/labels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateLabels(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: UpdateGithubIssueLabelsReqDto,
    ) = updateGithubIssueLabelsService.execute(userId, projectId, repoId, issueNumber, request)

    @Operation(summary = "이슈 댓글 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 이슈 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"502 BAD_GATEWAY","code":502,"message":"요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @GetMapping("/comments")
    fun listComments(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
    ): List<GithubCommentResDto> = listGithubCommentsService.execute(userId, projectId, repoId, issueNumber)

    @Operation(
        summary = "이슈 댓글 작성 요청",
        description = "응답은 요청 접수만 확인합니다. 생성된 댓글은 댓글 목록에서 확인할 수 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "프로젝트 읽기 권한 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트 또는 등록된 저장소 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @RequestBody request: CreateGithubCommentReqDto,
    ) = createGithubCommentService.execute(
        userId,
        projectId,
        repoId,
        GithubCommentParentType.ISSUE,
        issueNumber,
        request,
    )

    @Operation(
        summary = "이슈 댓글 수정 요청",
        description = "응답은 요청 접수만 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 저장소 URL 또는 연결된 GitHub 계정 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 댓글 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"502 BAD_GATEWAY","code":502,"message":"요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PatchMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateGithubCommentReqDto,
    ) = updateGithubCommentService.execute(userId, projectId, repoId, commentId, request)

    @Operation(
        summary = "이슈 댓글 삭제 요청",
        description = "응답은 요청 접수만 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "요청 접수 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "등록된 GitHub 저장소 URL 형식 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"400 BAD_REQUEST","code":400,"message":"요청 값이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "본인이 작성한 댓글이 아니며 프로젝트 수정 권한도 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"403 FORBIDDEN","code":403,"message":"요청을 수행할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "프로젝트, 등록된 저장소 또는 댓글 없음",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"404 NOT_FOUND","code":404,"message":"요청한 대상을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "GitHub 연동 오류",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"502 BAD_GATEWAY","code":502,"message":"요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteComment(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable repoId: Long,
        @PathVariable issueNumber: Int,
        @PathVariable commentId: Long,
    ) = deleteGithubCommentService.execute(userId, projectId, repoId, commentId)
}

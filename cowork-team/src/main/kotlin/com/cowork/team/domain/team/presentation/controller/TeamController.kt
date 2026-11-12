package com.cowork.team.domain.team.presentation.controller

import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.request.IconConfirmRequest
import com.cowork.team.domain.team.presentation.data.request.IconPresignedUrlRequest
import com.cowork.team.domain.team.presentation.data.request.UpdateIconRequest
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.CreateTeamService
import com.cowork.team.domain.team.service.DeleteTeamIconService
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.QueryMyTeamsService
import com.cowork.team.domain.team.service.QueryTeamService
import com.cowork.team.domain.team.service.UpdateTeamIconService
import com.cowork.team.domain.team.service.UpdateTeamService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "팀", description = "팀을 생성하고 팀 정보를 조회하거나 수정합니다.")
@ApiResponses(
    ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CommonApiResponse::class))],
    ),
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
@RequestMapping("/teams")
class TeamController(
    private val generateIconPresignedUrlService: GenerateIconPresignedUrlService,
    private val confirmIconUploadService: ConfirmIconUploadService,
    private val createTeamService: CreateTeamService,
    private val queryMyTeamsService: QueryMyTeamsService,
    private val queryTeamService: QueryTeamService,
    private val updateTeamService: UpdateTeamService,
    private val updateTeamIconService: UpdateTeamIconService,
    private val deleteTeamIconService: DeleteTeamIconService,
    private val deleteTeamService: DeleteTeamService,
) {

    @Operation(summary = "팀 아이콘 업로드 URL 발급", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "400",
            description = "허용되지 않는 파일 형식",
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
    )
    @PostMapping("/icon/presigned")
    fun generateIconPresignedUrl(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: IconPresignedUrlRequest,
    ): IconPresignedUrlResponse = generateIconPresignedUrlService.execute(userId, request.contentType)

    @Operation(summary = "팀 아이콘 업로드 확인", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "확인 성공"),
        ApiResponse(
            responseCode = "400",
            description = "유효하지 않은 objectKey",
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
            description = "다른 사용자의 업로드 또는 폐기된 업로드",
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
            description = "발급된 아이콘 업로드 없음",
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
            responseCode = "409",
            description = "파일 업로드 미완료",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"409 CONFLICT","code":409,"message":"현재 상태에서는 요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "413",
            description = "파일 크기 초과",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"413 CONTENT_TOO_LARGE","code":413,"message":"허용된 파일 크기를 초과했습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping("/icon/confirm")
    fun confirmIconUpload(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: IconConfirmRequest,
    ): IconConfirmResponse = confirmIconUploadService.execute(userId, request.objectKey)

    @Operation(summary = "팀 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "팀 생성 성공",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "201 CREATED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "201"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Created"),
                        ), SchemaProperty(name = "data", schema = Schema(implementation = TeamResponse::class)),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "팀 이름이 비어 있거나 100자를 초과함",
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
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @Valid @RequestBody request: CreateTeamRequest,
    ): TeamResponse = createTeamService.execute(userId, request)

    @Operation(summary = "내 팀 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    fun getMyTeams(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long): List<TeamSummaryResponse> =
        queryMyTeamsService.execute(userId)

    @Operation(summary = "팀 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음",
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
            description = "팀 없음",
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
    @GetMapping("/{teamId}")
    fun getTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): TeamResponse = queryTeamService.execute(userId, teamId)

    @Operation(summary = "팀 정보 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "팀 이름이 비어 있거나 100자를 초과함",
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
            description = "권한 없음",
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
            description = "팀 없음",
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
    @PatchMapping("/{teamId}")
    fun updateTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @Valid @RequestBody request: UpdateTeamRequest,
    ): TeamResponse = updateTeamService.execute(userId, teamId, request)

    @Operation(summary = "팀 아이콘 교체", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "교체 성공"),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음",
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
            description = "팀 없음",
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
    @PatchMapping("/{teamId}/icon")
    fun updateIcon(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateIconRequest,
    ): IconConfirmResponse = updateTeamIconService.execute(userId, teamId, request.iconUrl)

    @Operation(summary = "팀 아이콘 제거", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음",
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
            description = "팀 없음 또는 아이콘 없음",
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
    @DeleteMapping("/{teamId}/icon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteIcon(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long, @PathVariable teamId: Long) {
        deleteTeamIconService.execute(userId, teamId)
    }

    @Operation(summary = "팀 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음",
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
            description = "팀 없음",
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
    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTeam(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long, @PathVariable teamId: Long) {
        deleteTeamService.execute(userId, teamId)
    }
}

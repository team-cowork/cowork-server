package com.cowork.team.domain.teamInvite.presentation.controller

import com.cowork.team.domain.team.presentation.data.response.JoinTeamResponse
import com.cowork.team.domain.teamInvite.presentation.data.request.CreateInviteRequest
import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse
import com.cowork.team.domain.teamInvite.service.CreateInviteService
import com.cowork.team.domain.teamInvite.service.DeleteInviteService
import com.cowork.team.domain.teamInvite.service.JoinTeamService
import com.cowork.team.domain.teamInvite.service.QueryInvitesService
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
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "팀 초대", description = "팀 초대 링크 관리와 초대 코드를 통한 팀 가입")
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
@RequestMapping("/teams")
class TeamInviteController(
    private val createInviteService: CreateInviteService,
    private val queryInvitesService: QueryInvitesService,
    private val deleteInviteService: DeleteInviteService,
    private val joinTeamService: JoinTeamService,
) {

    @Operation(summary = "초대 링크 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "생성 성공",
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
                        ), SchemaProperty(name = "data", schema = Schema(implementation = InviteResponse::class)),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "팀 멤버가 아님",
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
    @PostMapping("/{teamId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    fun createInvite(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: CreateInviteRequest,
    ): InviteResponse = createInviteService.execute(userId, teamId, request)

    @Operation(summary = "초대 링크 목록 조회 (만료 포함)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "팀 멤버가 아님",
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
    )
    @GetMapping("/{teamId}/invites")
    fun getInvites(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<InviteResponse> = queryInvitesService.execute(userId, teamId)

    @Operation(summary = "초대 링크 무효화", security = [SecurityRequirement(name = "BearerAuth")])
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
            description = "초대 링크 없음",
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
    @DeleteMapping("/{teamId}/invites/{inviteCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteInvite(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable inviteCode: String,
    ) {
        deleteInviteService.execute(userId, teamId, inviteCode)
    }

    @Operation(summary = "초대 코드로 팀 가입", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "가입 성공"),
        ApiResponse(
            responseCode = "404",
            description = "유효하지 않은 초대 코드",
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
            description = "이미 팀 멤버",
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
            responseCode = "410",
            description = "만료된 초대 링크",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = CommonApiResponse::class),
                    examples = [
                        ExampleObject(
                            value = """{"status":"410 GONE","code":410,"message":"요청을 처리할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @PostMapping("/join/{inviteCode}")
    fun joinTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable inviteCode: String,
    ): JoinTeamResponse = joinTeamService.execute(userId, inviteCode)
}

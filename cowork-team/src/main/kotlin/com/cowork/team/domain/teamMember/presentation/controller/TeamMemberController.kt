package com.cowork.team.domain.teamMember.presentation.controller

import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeNicknameRequest
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.service.ChangeTeamMemberNicknameService
import com.cowork.team.domain.teamMember.service.ChangeTeamMemberRoleService
import com.cowork.team.domain.teamMember.service.InviteTeamMembersService
import com.cowork.team.domain.teamMember.service.QueryTeamMembersService
import com.cowork.team.domain.teamMember.service.RemoveTeamMemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
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

@Tag(name = "팀 멤버")
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
@RequestMapping("/teams/{teamId}/members")
class TeamMemberController(
    private val inviteTeamMembersService: InviteTeamMembersService,
    private val queryTeamMembersService: QueryTeamMembersService,
    private val changeTeamMemberRoleService: ChangeTeamMemberRoleService,
    private val changeTeamMemberNicknameService: ChangeTeamMemberNicknameService,
    private val removeTeamMemberService: RemoveTeamMemberService,
) {

    @Operation(summary = "멤버 초대", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "초대 성공",
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
                        ), SchemaProperty(
                            name = "data",
                            array = ArraySchema(schema = Schema(implementation = TeamMemberResponse::class)),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "초대할 ID가 양수가 아니거나 한 번에 초대할 수 있는 인원 초과",
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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun inviteMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: InviteMembersRequest,
    ): List<TeamMemberResponse> = inviteTeamMembersService.execute(userId, teamId, request)

    @Operation(summary = "멤버 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
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
    )
    @GetMapping
    fun getMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<TeamMemberResponse> = queryTeamMembersService.execute(userId, teamId)

    @Operation(summary = "멤버 역할 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "변경 성공 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "OWNER 역할을 직접 지정함",
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
            description = "멤버 없음",
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
    @PatchMapping("/{targetUserId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @RequestBody request: ChangeRoleRequest,
    ) {
        changeTeamMemberRoleService.execute(userId, teamId, targetUserId, request)
    }

    @Operation(
        summary = "멤버 팀 닉네임 변경",
        description = "다른 멤버의 닉네임을 변경하려면 manage_nicknames 권한이 필요합니다. OWNER의 닉네임은 본인만 변경할 수 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "변경 성공 (응답 본문 없음)", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "닉네임 길이 초과",
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
            description = "멤버 없음",
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
    @PatchMapping("/{targetUserId}/nickname")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeNickname(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @RequestBody request: ChangeNicknameRequest,
    ) {
        changeTeamMemberNicknameService.execute(userId, teamId, targetUserId, request)
    }

    @Operation(summary = "팀 멤버 추방 또는 탈퇴", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "추방/탈퇴 성공", content = [Content()]),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음 또는 OWNER 탈퇴 시도",
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
            description = "멤버 없음",
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
    @DeleteMapping("/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ) {
        removeTeamMemberService.execute(userId, teamId, targetUserId)
    }
}

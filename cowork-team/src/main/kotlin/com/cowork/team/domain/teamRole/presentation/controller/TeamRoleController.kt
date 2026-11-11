package com.cowork.team.domain.teamRole.presentation.controller

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.AssignTeamRoleService
import com.cowork.team.domain.teamRole.service.CreateTeamRoleService
import com.cowork.team.domain.teamRole.service.DeleteTeamRoleService
import com.cowork.team.domain.teamRole.service.QueryMemberRolesService
import com.cowork.team.domain.teamRole.service.QueryTeamRoleOperationService
import com.cowork.team.domain.teamRole.service.QueryTeamRolesService
import com.cowork.team.domain.teamRole.service.RevokeTeamRoleService
import com.cowork.team.domain.teamRole.service.UpdateTeamRoleService
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "팀 역할")
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
@RequestMapping("/teams/{teamId}")
class TeamRoleController(
    private val queryTeamRolesService: QueryTeamRolesService,
    private val queryMemberRolesService: QueryMemberRolesService,
    private val queryTeamRoleOperationService: QueryTeamRoleOperationService,
    private val createTeamRoleService: CreateTeamRoleService,
    private val updateTeamRoleService: UpdateTeamRoleService,
    private val deleteTeamRoleService: DeleteTeamRoleService,
    private val assignTeamRoleService: AssignTeamRoleService,
    private val revokeTeamRoleService: RevokeTeamRoleService,
) {

    @Operation(summary = "역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
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
    @GetMapping("/roles")
    fun getRoles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<TeamRoleResponse> = queryTeamRolesService.execute(userId, teamId)

    @Operation(summary = "멤버 역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
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
            description = "대상 멤버 없음",
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
    @GetMapping("/members/{targetUserId}/roles")
    fun getMemberRoles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ): List<TeamRoleResponse> = queryMemberRolesService.execute(userId, teamId, targetUserId)

    @Operation(summary = "역할 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "생성 요청 접수. operationId로 처리 결과를 조회합니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "202 ACCEPTED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "202"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Accepted"),
                        ), SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TeamRoleOperationResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "기본 역할 이름 또는 잘못된 역할 설정",
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
            responseCode = "409",
            description = "Idempotency-Key 재사용 충돌",
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
    )
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @RequestBody request: CreateTeamRoleRequest,
    ): TeamRoleOperationResponse = createTeamRoleService.execute(userId, teamId, idempotencyKey, request)

    @Operation(summary = "역할 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "수정 요청 접수. operationId로 처리 결과를 조회합니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "202 ACCEPTED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "202"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Accepted"),
                        ), SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TeamRoleOperationResponse::class),
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
            description = "역할 없음",
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
            description = "Idempotency-Key 재사용 충돌",
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
    )
    @PatchMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleOperationResponse = updateTeamRoleService.execute(userId, teamId, roleId, idempotencyKey, request)

    @Operation(summary = "역할 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "삭제 요청 접수. operationId로 처리 결과를 조회합니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "202 ACCEPTED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "202"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Accepted"),
                        ), SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TeamRoleOperationResponse::class),
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
            description = "역할 없음",
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
            description = "Idempotency-Key 재사용 충돌",
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
    )
    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = deleteTeamRoleService.execute(userId, teamId, roleId, idempotencyKey)

    @Operation(summary = "멤버에게 역할 부여", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "부여 요청 접수. operationId로 처리 결과를 조회합니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "202 ACCEPTED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "202"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Accepted"),
                        ), SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TeamRoleOperationResponse::class),
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
            description = "대상 멤버 또는 역할 없음",
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
            description = "Idempotency-Key 재사용 충돌",
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
    )
    @PutMapping("/members/{targetUserId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun assignRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = assignTeamRoleService.execute(
        userId,
        teamId,
        targetUserId,
        roleId,
        idempotencyKey,
    )

    @Operation(summary = "멤버 역할 회수", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "회수 요청 접수. operationId로 처리 결과를 조회합니다.",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(type = "object", requiredProperties = ["status", "code", "message", "data"]),
                    schemaProperties = [
                        SchemaProperty(
                            name = "status",
                            schema = Schema(implementation = HttpStatus::class, example = "202 ACCEPTED"),
                        ), SchemaProperty(
                            name = "code",
                            schema = Schema(type = "integer", example = "202"),
                        ), SchemaProperty(
                            name = "message",
                            schema = Schema(type = "string", example = "Accepted"),
                        ), SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = TeamRoleOperationResponse::class),
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
            description = "대상 멤버 또는 역할 없음",
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
            description = "Idempotency-Key 재사용 충돌",
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
    )
    @DeleteMapping("/members/{targetUserId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun revokeRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = revokeTeamRoleService.execute(
        userId,
        teamId,
        targetUserId,
        roleId,
        idempotencyKey,
    )

    @Operation(
        summary = "팀 역할 비동기 작업 상태 조회",
        description = "검증에 실패하면 상태는 FAILED입니다. errorCode와 errorMessage에서 원인을 확인할 수 있습니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "작업 없음",
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
    @GetMapping("/role-operations/{operationId}")
    fun getOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable operationId: String,
    ): TeamRoleOperationResponse = queryTeamRoleOperationService.execute(userId, teamId, operationId)
}

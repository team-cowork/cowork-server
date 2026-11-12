package com.cowork.channel.domain.channelRolePolicy.presentation.controller

import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.DeleteChannelRolePolicyService
import com.cowork.channel.domain.channelRolePolicy.service.QueryChannelRolePolicyOperationService
import com.cowork.channel.domain.channelRolePolicy.service.UpsertChannelRolePolicyService
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.response.CommonApiResponse

@Tag(name = "채널 역할 정책", description = "역할별 채널 권한 덮어쓰기 정책 API")
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
@RequestMapping("/channels/{channelId}/role-policies/{roleId}")
class ChannelRolePolicyController(
    private val upsertService: UpsertChannelRolePolicyService,
    private val deleteService: DeleteChannelRolePolicyService,
    private val queryOperationService: QueryChannelRolePolicyOperationService,
) {
    @Operation(summary = "채널 역할 정책 설정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "설정 요청 접수. operationId로 처리 결과를 조회합니다.",
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
                        ), SchemaProperty(name = "message", schema = Schema(type = "string", example = "Accepted")),

                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = ChannelRolePolicyOperationResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "permissions 계약 위반 또는 DM 채널",
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
            description = "채널 관리 권한 없음",
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
            description = "채널 또는 역할 없음",
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
    @PutMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upsert(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") actorId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable channelId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpsertChannelRolePolicyRequest,
    ): ChannelRolePolicyOperationResponse = upsertService.execute(
        actorId,
        channelId,
        roleId,
        idempotencyKey,
        request,
    )

    @Operation(summary = "채널 역할 정책 삭제", security = [SecurityRequirement(name = "BearerAuth")])
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
                        ), SchemaProperty(name = "message", schema = Schema(type = "string", example = "Accepted")),

                        SchemaProperty(
                            name = "data",
                            schema = Schema(implementation = ChannelRolePolicyOperationResponse::class),
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "채널 관리 권한 없음",
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
            description = "채널, 역할 또는 정책 없음",
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
    @DeleteMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") actorId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable channelId: Long,
        @PathVariable roleId: Long,
    ): ChannelRolePolicyOperationResponse = deleteService.execute(actorId, channelId, roleId, idempotencyKey)

    @Operation(
        summary = "채널 역할 정책 비동기 작업 상태 조회",
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
    @GetMapping("/operations/{operationId}")
    fun getOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") actorId: Long,
        @PathVariable channelId: Long,
        @PathVariable roleId: Long,
        @PathVariable operationId: String,
    ): ChannelRolePolicyOperationResponse = queryOperationService.execute(actorId, channelId, roleId, operationId)
}

package com.cowork.channel.domain.channelRolePolicy.presentation.controller

import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.DeleteChannelRolePolicyService
import com.cowork.channel.domain.channelRolePolicy.service.QueryChannelRolePolicyOperationService
import com.cowork.channel.domain.channelRolePolicy.service.UpsertChannelRolePolicyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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

@Tag(name = "채널 역할 정책", description = "역할별 채널 message_read IAM 정책 API")
@RestController
@RequestMapping("/channels/{channelId}/role-policies/{roleId}")
class ChannelRolePolicyController(
    private val upsertService: UpsertChannelRolePolicyService,
    private val deleteService: DeleteChannelRolePolicyService,
    private val queryOperationService: QueryChannelRolePolicyOperationService,
) {
    @Operation(summary = "채널 역할 정책 설정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 설정 요청 접수"),
        ApiResponse(responseCode = "400", description = "permissions 계약 위반 또는 DM 채널"),
        ApiResponse(responseCode = "403", description = "채널 관리 권한 없음"),
        ApiResponse(responseCode = "404", description = "채널 또는 역할 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
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
        ApiResponse(responseCode = "202", description = "비동기 삭제 요청 접수"),
        ApiResponse(responseCode = "403", description = "채널 관리 권한 없음"),
        ApiResponse(responseCode = "404", description = "채널, 역할 또는 정책 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
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
        description = "소유 서비스 검증 실패는 FAILED 상태와 errorCode/errorMessage로 반환됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
        ApiResponse(responseCode = "404", description = "작업 없음"),
    )
    @GetMapping("/operations/{operationId}")
    fun getOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") actorId: Long,
        @PathVariable channelId: Long,
        @PathVariable roleId: Long,
        @PathVariable operationId: String,
    ): ChannelRolePolicyOperationResponse = queryOperationService.execute(actorId, channelId, roleId, operationId)
}

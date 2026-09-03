package com.cowork.channel.domain.channelRolePolicy.presentation.controller

import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyResponse
import com.cowork.channel.domain.channelRolePolicy.service.QueryChannelRolePoliciesService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "채널 역할 정책", description = "역할별 채널 message_read IAM 정책 API")
@RestController
@RequestMapping("/channels/{channelId}/role-policies")
class ChannelRolePolicyQueryController(private val queryService: QueryChannelRolePoliciesService) {
    @Operation(summary = "채널 역할 정책 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "우선순위 내림차순 정책 조회 성공"),
        ApiResponse(responseCode = "400", description = "DM 채널"),
        ApiResponse(responseCode = "403", description = "채널 관리 권한 없음"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping
    fun getPolicies(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") actorId: Long,
        @PathVariable channelId: Long,
    ): List<ChannelRolePolicyResponse> = queryService.execute(actorId, channelId)
}

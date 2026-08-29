package com.cowork.channel.domain.channelRolePolicy.presentation.data.response

import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationStatus
import io.swagger.v3.oas.annotations.media.Schema

data class ChannelRolePolicyOperationResponse(
    @field:Schema(description = "서버가 발급한 비동기 작업 UUID")
    val operationId: String,
    @field:Schema(description = "비동기 작업 상태")
    val status: ChannelRolePolicyOperationStatus,
    @field:Schema(description = "성공한 정책의 최종 permissions")
    val permissions: Map<String, Boolean>? = null,
    @field:Schema(description = "실패 코드")
    val errorCode: String? = null,
    @field:Schema(description = "실패 사유")
    val errorMessage: String? = null,
)

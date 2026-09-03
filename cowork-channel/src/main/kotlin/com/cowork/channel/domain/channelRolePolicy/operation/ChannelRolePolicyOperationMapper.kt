package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class ChannelRolePolicyOperationMapper(private val objectMapper: ObjectMapper) {
    fun toResponse(operation: ChannelRolePolicyCommandOperation): ChannelRolePolicyOperationResponse =
        ChannelRolePolicyOperationResponse(
            operationId = operation.operationId,
            status = operation.status,
            permissions = operation.resultPermissionsJson?.let {
                objectMapper.readValue(it, object : TypeReference<Map<String, Boolean>>() {})
            },
            errorCode = operation.errorCode,
            errorMessage = operation.errorMessage,
        )
}

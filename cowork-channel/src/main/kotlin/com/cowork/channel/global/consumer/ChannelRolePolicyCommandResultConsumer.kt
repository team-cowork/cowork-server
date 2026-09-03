package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperation
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperationRepository
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandResultPayload
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyContractTopics
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationProjectionFinalizer
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationStatus
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class ChannelRolePolicyCommandResultConsumer(
    private val operationRepository: ChannelRolePolicyCommandOperationRepository,
    private val finalizer: ChannelRolePolicyOperationProjectionFinalizer,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [ChannelRolePolicyContractTopics.RESULT],
        groupId = "cowork-channel.channel-role-policy-command-result",
        containerFactory = "channelRolePolicyCommandResultListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        val result = runCatching {
            objectMapper.readValue(record.value(), ChannelRolePolicyCommandResultPayload::class.java)
        }.getOrElse { throw IllegalArgumentException("channel role policy command result JSON이 유효하지 않습니다.", it) }
        require(result.schemaVersion == 1) { "지원하지 않는 channel role policy result schemaVersion입니다." }
        require(runCatching { UUID.fromString(result.operationId) }.isSuccess) {
            "channel role policy result operationId는 UUID여야 합니다."
        }
        require(result.idempotencyKey.isNotBlank() && result.idempotencyKey.length <= 128) {
            "channel role policy result idempotencyKey는 1~128자여야 합니다."
        }
        require(REQUEST_HASH.matches(result.requestHash)) {
            "channel role policy result requestHash가 유효하지 않습니다."
        }
        require(record.key() == result.operationId) {
            "channel role policy command result key가 operationId와 일치하지 않습니다."
        }
        require(
            result.teamId > 0 &&
                result.channelId > 0 &&
                result.roleId > 0,
        ) { "channel role policy result 식별자가 유효하지 않습니다." }
        require(result.status in TERMINAL_STATUSES) {
            "channel role policy command result는 terminal 상태여야 합니다."
        }

        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "channel role policy result에 대응하는 operation이 없습니다: ${result.operationId}"
        }
        require(operation.teamId == result.teamId) { "result teamId가 일치하지 않습니다." }
        require(operation.channelId == result.channelId) { "result channelId가 일치하지 않습니다." }
        require(operation.roleId == result.roleId) { "result roleId가 일치하지 않습니다." }
        require(operation.commandType == result.commandType) { "result commandType이 일치하지 않습니다." }
        require(operation.requestHash == result.requestHash) { "result requestHash가 일치하지 않습니다." }
        require(operation.idempotencyKey == result.idempotencyKey) { "result idempotencyKey가 일치하지 않습니다." }

        when (result.status) {
            ChannelRolePolicyOperationStatus.FAILED -> handleFailure(operation, result)
            ChannelRolePolicyOperationStatus.SUCCEEDED -> handleSuccess(operation, result)
            ChannelRolePolicyOperationStatus.PENDING, ChannelRolePolicyOperationStatus.PROCESSING ->
                error("unreachable result status")
        }
    }

    private fun handleFailure(
        operation: ChannelRolePolicyCommandOperation,
        result: ChannelRolePolicyCommandResultPayload,
    ) {
        val error = requireNotNull(result.error) { "FAILED result에는 error가 필요합니다." }
        require(result.permissions == null && result.projection == null) {
            "FAILED result에는 permissions 또는 projection이 포함될 수 없습니다."
        }
        require(error.code.isNotBlank() && error.code.length <= 100) { "FAILED error code가 유효하지 않습니다." }
        require(error.message.isNotBlank() && error.message.length <= 1000) {
            "FAILED error message가 유효하지 않습니다."
        }
        operation.markFailed(error.code, error.message)
    }

    private fun handleSuccess(
        operation: ChannelRolePolicyCommandOperation,
        result: ChannelRolePolicyCommandResultPayload,
    ) {
        require(result.error == null) { "SUCCEEDED result에는 error가 포함될 수 없습니다." }
        val expectation = requireNotNull(result.projection) {
            "SUCCEEDED result에는 projection expectation이 필요합니다."
        }
        val expectedKey = ChannelRolePolicyProjection.key(result.teamId, result.channelId, result.roleId)
        require(expectation.key == expectedKey) { "projection key가 유효하지 않습니다." }
        val deleted = result.commandType == ChannelRolePolicyCommandType.DELETE
        require(expectation.deleted == deleted) { "result projection deleted 계약이 유효하지 않습니다." }
        if (result.commandType == ChannelRolePolicyCommandType.UPSERT) {
            require(
                result.permissions?.keys == setOf(MESSAGE_READ_KEY) &&
                    result.permissions[MESSAGE_READ_KEY] != null,
            ) {
                "UPSERT result permissions에는 message_read boolean 하나만 필요합니다."
            }
        } else {
            require(result.permissions == null) { "DELETE result에는 permissions가 없어야 합니다." }
        }
        operation.markProcessing(result.permissions?.let(objectMapper::writeValueAsString), expectation)
        finalizer.tryFinalize(operation)
    }

    private companion object {
        val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
        val TERMINAL_STATUSES = setOf(
            ChannelRolePolicyOperationStatus.SUCCEEDED,
            ChannelRolePolicyOperationStatus.FAILED,
        )
        const val MESSAGE_READ_KEY = "message_read"
    }
}

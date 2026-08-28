package com.cowork.team.global.consumer

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperationRepository
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandResultPayload
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.operation.TeamRoleContractTopics
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationProjectionFinalizer
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class PreferenceTeamRoleCommandResultConsumer(
    private val operationRepository: TeamRoleCommandOperationRepository,
    private val finalizer: TeamRoleOperationProjectionFinalizer,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [TeamRoleContractTopics.RESULT],
        groupId = "\${KAFKA_GROUP_ID_TEAM_ROLE_COMMAND_RESULT:cowork-team.team-role-command-result}",
        containerFactory = "teamRoleCommandResultListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        val result = runCatching {
            objectMapper.readValue(record.value(), TeamRoleCommandResultPayload::class.java)
        }.getOrElse { throw IllegalArgumentException("팀 역할 command result JSON이 유효하지 않습니다.", it) }
        require(result.schemaVersion == 1) { "지원하지 않는 팀 역할 command result schemaVersion입니다." }
        require(runCatching { UUID.fromString(result.operationId) }.isSuccess) {
            "팀 역할 command result operationId는 UUID여야 합니다."
        }
        require(result.idempotencyKey.isNotBlank() && result.idempotencyKey.length <= 128) {
            "팀 역할 command result idempotencyKey는 1~128자여야 합니다."
        }
        require(REQUEST_HASH.matches(result.requestHash)) {
            "팀 역할 command result requestHash가 유효하지 않습니다."
        }
        require(record.key() == result.operationId) { "팀 역할 command result key가 operationId와 일치하지 않습니다." }
        require(result.status == TeamRoleOperationStatus.SUCCEEDED || result.status == TeamRoleOperationStatus.FAILED) {
            "팀 역할 command result는 terminal 상태여야 합니다."
        }

        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "팀 역할 command result에 대응하는 operation이 없습니다: ${result.operationId}"
        }
        require(operation.teamId == result.teamId) { "팀 역할 command result의 teamId가 일치하지 않습니다." }
        require(operation.commandType == result.commandType) { "팀 역할 command result의 commandType이 일치하지 않습니다." }
        require(operation.requestHash == result.requestHash) { "팀 역할 command result의 requestHash가 일치하지 않습니다." }
        require(operation.idempotencyKey == result.idempotencyKey) {
            "팀 역할 command result의 idempotencyKey가 일치하지 않습니다."
        }

        when (result.status) {
            TeamRoleOperationStatus.FAILED -> {
                val error = requireNotNull(result.error) { "FAILED result에는 error가 필요합니다." }
                require(result.role == null && result.projection == null) {
                    "FAILED result에는 role 또는 projection이 포함될 수 없습니다."
                }
                require(error.code.isNotBlank() && error.code.length <= 100) {
                    "FAILED result error code는 1~100자여야 합니다."
                }
                require(error.message.isNotBlank() && error.message.length <= 1000) {
                    "FAILED result error message는 1~1000자여야 합니다."
                }
                operation.markFailed(error.code, error.message)
            }
            TeamRoleOperationStatus.SUCCEEDED -> {
                require(result.error == null) { "SUCCEEDED result에는 error가 포함될 수 없습니다." }
                val expectation = requireNotNull(result.projection) {
                    "SUCCEEDED result에는 projection expectation이 필요합니다."
                }
                require(expectation.key.isNotBlank() && expectation.key.length <= 512) {
                    "projection key는 1~512자여야 합니다."
                }
                require(expectation.occurredAt == result.occurredAt) {
                    "SUCCEEDED result의 occurredAt과 projection occurredAt이 일치해야 합니다."
                }
                validateSucceededResult(result)
                operation.markProcessing(result.role?.let(objectMapper::writeValueAsString), expectation)
                finalizer.tryFinalize(operation)
            }
            TeamRoleOperationStatus.PENDING, TeamRoleOperationStatus.PROCESSING ->
                error("unreachable result status")
        }
    }

    private fun validateSucceededResult(result: TeamRoleCommandResultPayload) {
        val expectsRole = result.commandType != TeamRoleCommandType.DELETE
        require((result.role != null) == expectsRole) {
            "${result.commandType} SUCCEEDED result의 role 계약이 유효하지 않습니다."
        }
        result.role?.let { role ->
            require(role.id > 0 && role.teamId == result.teamId) { "result role 식별자가 유효하지 않습니다." }
            require(role.name.isNotBlank() && role.name.length <= 50) { "result role 이름이 유효하지 않습니다." }
            require(COLOR_HEX.matches(role.colorHex)) { "result role 색상이 유효하지 않습니다." }
        }
        val expectedDeleted = result.commandType in setOf(
            TeamRoleCommandType.DELETE,
            TeamRoleCommandType.REVOKE,
        )
        require(result.projection?.deleted == expectedDeleted) {
            "${result.commandType} SUCCEEDED result의 projection deleted 계약이 유효하지 않습니다."
        }
        val keyPrefix = when (result.commandType) {
            TeamRoleCommandType.CREATE, TeamRoleCommandType.UPDATE, TeamRoleCommandType.DELETE ->
                "role:${result.teamId}:"
            TeamRoleCommandType.ASSIGN, TeamRoleCommandType.REVOKE -> "assignment:${result.teamId}:"
        }
        require(requireNotNull(result.projection).key.startsWith(keyPrefix)) {
            "${result.commandType} SUCCEEDED result의 projection key가 유효하지 않습니다."
        }
    }

    private companion object {
        val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
        val COLOR_HEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

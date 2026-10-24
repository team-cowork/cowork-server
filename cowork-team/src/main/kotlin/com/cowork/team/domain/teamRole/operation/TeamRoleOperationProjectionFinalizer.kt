package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

@Component
class TeamRoleOperationProjectionFinalizer(
    private val operationRepository: TeamRoleCommandOperationRepository,
    private val roleRepository: TeamRoleProjectionRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val objectMapper: ObjectMapper,
) {
    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 저장 결과 보정 완료와 구형 producer·결과 재처리 종료 확인 후 이 주기 작업을 제거한다.
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    fun repairLegacyResults() {
        val repaired = operationRepository.repairLegacyResultRoles()
        if (repaired > 0) log.info("Repaired {} legacy team role operation results", repaired)
    }

    @Scheduled(fixedDelayString = "\${team-role.operation-finalizer-delay-ms:1000}")
    @Transactional
    fun finalizeVisibleOperations() {
        val status = TeamRoleOperationStatus.PROCESSING
        val throughOperationId = operationRepository.findTopByStatusOrderByOperationIdDesc(status)?.operationId
            ?: return
        var afterOperationId = ""
        do {
            val batch = operationRepository.findStatusBatch(
                status,
                afterOperationId,
                throughOperationId,
                PageRequest.of(0, FINALIZE_BATCH_SIZE),
            )
            if (batch.isEmpty()) return
            batch.forEach(::tryFinalize)
            afterOperationId = batch.last().operationId
        } while (afterOperationId < throughOperationId)
    }

    fun tryFinalize(operation: TeamRoleCommandOperation): Boolean {
        if (operation.status != TeamRoleOperationStatus.PROCESSING) return false
        val key = operation.expectedProjectionKey ?: return false
        val occurredAt = operation.expectedProjectionOccurredAt ?: return false
        val expectedDeleted = operation.expectedProjectionDeleted ?: return false
        val visible = when {
            key.startsWith("role:") -> key.substringAfterLast(':').toLongOrNull()?.let { roleId ->
                roleRepository.findById(roleId).orElse(null)?.let { projection ->
                    roleProjectionMatches(operation, projection, occurredAt, expectedDeleted)
                }
            } == true

            key.startsWith("assignment:") -> {
                val projectionKey = key.removePrefix("assignment:")
                assignmentRepository.findById(projectionKey).orElse(null)?.let { projection ->
                    if (projection.teamId != operation.teamId) {
                        false
                    } else {
                        when {
                            projection.sourceOccurredAt.isBefore(occurredAt) -> false
                            projection.sourceOccurredAt.isAfter(occurredAt) -> true
                            else -> projection.deleted == expectedDeleted
                        }
                    }
                } == true
            }

            else -> false
        }
        if (visible) operation.markSucceeded()
        return visible
    }

    private fun roleProjectionMatches(
        operation: TeamRoleCommandOperation,
        projection: TeamRoleProjection,
        expectedOccurredAt: Instant,
        expectedDeleted: Boolean,
    ): Boolean {
        if (projection.teamId != operation.teamId || projection.sourceOccurredAt.isBefore(expectedOccurredAt)) {
            return false
        }
        if (projection.sourceOccurredAt.isAfter(expectedOccurredAt)) return true
        if (projection.deleted != expectedDeleted) return false
        if (expectedDeleted) return true

        val expectedRole = operation.resultRoleJson?.let {
            objectMapper.readValue(it, TeamRoleResultRole::class.java)
        } ?: return false
        return projection.roleId == expectedRole.id &&
            projection.teamId == expectedRole.teamId &&
            projection.name == expectedRole.name &&
            projection.colorHex == expectedRole.colorHex &&
            projection.priority == expectedRole.priority &&
            projection.mentionable == expectedRole.mentionable &&
            projection.isDefault == expectedRole.isDefault &&
            objectMapper.readValue<Map<String, Boolean>>(projection.permissionsJson) == expectedRole.permissions
    }

    private companion object {
        val log = LoggerFactory.getLogger(TeamRoleOperationProjectionFinalizer::class.java)
        const val FINALIZE_BATCH_SIZE = 100
    }
}

package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class ChannelRolePolicyOperationProjectionFinalizer(
    private val operationRepository: ChannelRolePolicyCommandOperationRepository,
    private val policyRepository: ChannelRolePolicyProjectionRepository,
    private val objectMapper: ObjectMapper,
) {
    @Scheduled(fixedDelayString = "\${channel-role-policy.operation-finalizer-delay-ms:1000}")
    @Transactional
    fun finalizeVisibleOperations() {
        val status = ChannelRolePolicyOperationStatus.PROCESSING
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

    fun tryFinalize(operation: ChannelRolePolicyCommandOperation): Boolean {
        if (operation.status != ChannelRolePolicyOperationStatus.PROCESSING) return false
        val key = operation.expectedProjectionKey ?: return false
        val occurredAt = operation.expectedProjectionOccurredAt ?: return false
        val expectedDeleted = operation.expectedProjectionDeleted ?: return false
        if (key != ChannelRolePolicyProjection.key(operation.teamId, operation.channelId, operation.roleId)) {
            return false
        }
        val projection = policyRepository.findById(key).orElse(null) ?: return false
        val visible = projection.teamId == operation.teamId &&
            projection.channelId == operation.channelId &&
            projection.roleId == operation.roleId &&
            projectionMatches(operation, projection, occurredAt, expectedDeleted)
        if (visible) operation.markSucceeded()
        return visible
    }

    private fun projectionMatches(
        operation: ChannelRolePolicyCommandOperation,
        projection: ChannelRolePolicyProjection,
        expectedOccurredAt: Instant,
        expectedDeleted: Boolean,
    ): Boolean {
        if (projection.sourceOccurredAt.isBefore(expectedOccurredAt)) return false
        if (projection.sourceOccurredAt.isAfter(expectedOccurredAt)) return true
        if (projection.deleted != expectedDeleted) return false
        if (expectedDeleted) return true
        val expectedPermissions = operation.resultPermissionsJson?.let(::readPermissions) ?: return false
        return projection.permissionsJson?.let(::readPermissions) == expectedPermissions
    }

    private fun readPermissions(json: String): Map<String, Boolean> =
        objectMapper.readValue(json, object : TypeReference<Map<String, Boolean>>() {})

    private companion object {
        const val FINALIZE_BATCH_SIZE = 100
    }
}

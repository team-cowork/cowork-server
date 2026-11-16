package com.cowork.channel.global.consumer

import com.cowork.channel.domain.project.entity.ProjectProjection
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.toProjectionPrecision
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.logging.logger.logger
import java.time.Instant

@Service
class ProjectProjectionHandler(private val repository: ProjectProjectionRepository) {

    @Transactional
    fun apply(payload: ProjectEventPayload) {
        val occurredAt = payload.occurredAt?.toProjectionPrecision() ?: run {
            logger().warn("Ignore project event without occurredAt [projectId={}]", payload.projectId)
            return
        }
        when (payload.eventType) {
            "CREATED", "UPDATED" -> upsert(payload.projectId, payload.teamId, occurredAt)
            "DELETED" -> delete(payload.projectId, payload.teamId, occurredAt)
            else -> logger().warn("Ignore unsupported project event [eventType={}]", payload.eventType)
        }
    }

    private fun upsert(projectId: Long, teamId: Long, occurredAt: Instant) {
        val projection = repository.findByIdForUpdate(projectId) ?: run {
            ProjectProjection(projectId = projectId, teamId = teamId, sourceOccurredAt = occurredAt)
        }
        val existingVersion = projection.sourceOccurredAt.toProjectionPrecision()
        if (existingVersion.isAfter(occurredAt) ||
            (existingVersion == occurredAt && projection.deleted)
        ) {
            return
        }
        projection.applyUpsert(teamId, occurredAt)
        repository.save(projection)
    }

    private fun delete(projectId: Long, teamId: Long, occurredAt: Instant) {
        val projection = repository.findByIdForUpdate(projectId) ?: run {
            ProjectProjection(
                projectId = projectId,
                teamId = teamId,
                deleted = true,
                sourceOccurredAt = occurredAt,
            )
        }
        if (projection.sourceOccurredAt.toProjectionPrecision().isAfter(occurredAt)) return
        projection.markDeleted(occurredAt)
        repository.save(projection)
    }
}

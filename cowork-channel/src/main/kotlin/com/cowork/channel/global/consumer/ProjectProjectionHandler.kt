package com.cowork.channel.global.consumer

import com.cowork.channel.domain.project.entity.ProjectProjection
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.toProjectionPrecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProjectProjectionHandler(private val repository: ProjectProjectionRepository) {
    private val log = LoggerFactory.getLogger(ProjectProjectionHandler::class.java)

    @Transactional
    fun apply(payload: ProjectEventPayload) {
        val occurredAt = payload.occurredAt?.toProjectionPrecision() ?: run {
            log.warn("occurredAt이 없는 legacy project.event를 무시합니다 [projectId={}]", payload.projectId)
            return
        }
        when (payload.eventType) {
            "CREATED", "UPDATED" -> upsert(payload.projectId, payload.teamId, occurredAt)
            "DELETED" -> delete(payload.projectId, payload.teamId, occurredAt)
            else -> log.warn("알 수 없는 project.event를 무시합니다 [eventType={}]", payload.eventType)
        }
    }

    private fun upsert(projectId: Long, teamId: Long, occurredAt: Instant) {
        val projection = repository.findById(projectId).orElseGet {
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
        val projection = repository.findById(projectId).orElseGet {
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

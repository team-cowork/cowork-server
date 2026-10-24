package com.cowork.project.global.consumer

import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.toProjectionPrecision
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ProjectAccessStateConsumer(
    private val repository: ProjectAccessProjectionRepository,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
    private val mapper: ObjectMapper,
) {
    @KafkaListener(
        topics = ["preference.project-access.state.v1"],
        groupId = "cowork-project.project-access",
        containerFactory = "projectAccessListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = streams.projectAccess
        if (processor.processControlRecord(stream, record)) return
        val parsed = runCatching {
            val node = mapper.readTree(record.value())
            require(node.path("eventType").asText() == "UPSERT")
            require(node.path("projectId").isIntegralNumber && node.path("accountId").isIntegralNumber)
            val projectId = node.path("projectId").asLong()
            val userId = node.path("accountId").asLong()
            require(projectId > 0 && userId > 0 && record.key() == "$projectId:$userId")
            require(node.path("read").isBoolean && node.path("write").isBoolean)
            // TODO(#596): 구버전 생산자·롤백 지원 종료 및 구형 보관 레코드 교체 후 manage와 sourceMembershipVersion 필수화.
            require(!node.has("manage") || node.path("manage").isBoolean)
            require(!node.has("reorder") || node.path("reorder").isBoolean)
            ProjectAccessProjection(
                record.key(), projectId, userId,
                node.path("read").asBoolean(), node.path("write").asBoolean(),
                Instant.parse(node.path("occurredAt").asText()).toProjectionPrecision(),
                canReorder = node.path("reorder").asBoolean(false),
                canManage = node.path("manage").asBoolean(false),
                sourceMembershipVersion = if (node.has("sourceMembershipVersion")) {
                    Instant.parse(node.path("sourceMembershipVersion").asText()).toProjectionPrecision()
                } else Instant.EPOCH,
            )
        }
        val event = parsed.getOrElse {
            processor.quarantineRecord(stream, record, "Invalid project access state: ${it.message}")
            return
        }
        processor.applyRecord(stream, record) {
            val existing = repository.findById(event.accessKey).orElse(null)
            if (existing == null || event.sourceOccurredAt.isAfter(existing.sourceOccurredAt)) {
                repository.save(event)
            } else if (event.sourceOccurredAt == existing.sourceOccurredAt) {
                // Replayed snapshots are idempotent; conflicting equal versions never restore access.
                val sameMembership = existing.sourceMembershipVersion == event.sourceMembershipVersion
                val canRead = sameMembership && existing.canRead && event.canRead
                val canWrite = existing.canWrite && event.canWrite
                val canManage = existing.canManage && event.canManage
                val canReorder = existing.canReorder && event.canReorder
                if (existing.canRead != canRead || existing.canWrite != canWrite || existing.canManage != canManage || existing.canReorder != canReorder) {
                    existing.canManage = canManage
                    existing.canRead = canRead
                    existing.canWrite = canWrite
                    existing.canReorder = canReorder
                    repository.save(existing)
                }
            }
        }
    }
}

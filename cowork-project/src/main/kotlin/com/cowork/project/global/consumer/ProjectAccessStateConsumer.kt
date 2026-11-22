package com.cowork.project.global.consumer

import com.cowork.project.domain.calendar.event.ProjectReadableUserStatePublisher
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.ProjectionTopics
import com.cowork.project.global.projection.toProjectionPrecision
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class ProjectAccessStateConsumer(
    private val repository: ProjectAccessProjectionRepository,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
    private val mapper: ObjectMapper,
    private val projectReadableUserStatePublisher: ProjectReadableUserStatePublisher,
) {
    @KafkaListener(
        topics = [ProjectionTopics.PROJECT_ACCESS_STATE],
        groupId = "cowork-project.project-access",
        containerFactory = "projectAccessListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = streams.projectAccess
        if (processor.processControlRecord(stream, record)) return
        val parsed = runCatching {
            val node = mapper.readTree(record.value())
            require(node.path("eventType").asString() == "UPSERT")
            require(node.path("projectId").isIntegralNumber && node.path("accountId").isIntegralNumber)
            val projectId = node.path("projectId").asLong()
            val userId = node.path("accountId").asLong()
            require(projectId > 0 && userId > 0 && record.key() == "$projectId:$userId")
            require(node.path("read").isBoolean && node.path("write").isBoolean)
            require(node.path("manage").isBoolean)
            require(node.path("reorder").isBoolean)
            require(node.path("readPrivate").isBoolean)
            // Records published before board permissions existed omit these fields and grant no board access.
            BOARD_FIELDS.forEach { require(node.path(it).isMissingNode || node.path(it).isBoolean) }
            val membershipVersion = Instant.parse(node.path("sourceMembershipVersion").asString())
            require(node.has("sourceAuthorizationVersion"))
            val authorizationVersion = if (node.hasNonNull("sourceAuthorizationVersion")) {
                Instant.parse(node.path("sourceAuthorizationVersion").asString()).also {
                    require(it.isAfter(Instant.EPOCH) && !it.isAfter(membershipVersion))
                }.toProjectionPrecision()
            } else {
                null
            }
            require(
                authorizationVersion != null || GRANT_FIELDS.none {
                    node.path(it).asBoolean()
                },
            )
            ProjectAccessProjection(
                record.key(), projectId, userId,
                node.path("read").asBoolean(), node.path("write").asBoolean(),
                Instant.parse(node.path("occurredAt").asString()).toProjectionPrecision(),
                canReorder = node.path("reorder").asBoolean(),
                canManage = node.path("manage").asBoolean(),
                sourceMembershipVersion = membershipVersion.toProjectionPrecision(),
                sourceAuthorizationVersion = authorizationVersion,
                canReadPrivate = node.path("readPrivate").asBoolean(),
                canBoardRead = node.path("boardRead").asBoolean(),
                canBoardWrite = node.path("boardWrite").asBoolean(),
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
                val sameAuthorization = existing.sourceAuthorizationVersion != null &&
                    existing.sourceAuthorizationVersion == event.sourceAuthorizationVersion
                val canRead = sameMembership && sameAuthorization && existing.canRead && event.canRead
                val canReadPrivate = existing.privateReadForSameVersion(event)
                val canWrite = existing.canWrite && event.canWrite
                val canManage = existing.canManage && event.canManage
                val canReorder = existing.canReorder && event.canReorder
                val canBoardRead = existing.canBoardRead && event.canBoardRead
                val canBoardWrite = existing.canBoardWrite && event.canBoardWrite
                if (existing.canRead != canRead || existing.canWrite != canWrite || existing.canManage != canManage ||
                    existing.canReorder != canReorder ||
                    existing.canReadPrivate != canReadPrivate ||
                    existing.canBoardRead != canBoardRead ||
                    existing.canBoardWrite != canBoardWrite
                ) {
                    existing.canManage = canManage
                    existing.canRead = canRead
                    existing.canWrite = canWrite
                    existing.canReorder = canReorder
                    existing.canReadPrivate = canReadPrivate
                    existing.canBoardRead = canBoardRead
                    existing.canBoardWrite = canBoardWrite
                    repository.save(existing)
                }
            }
            projectReadableUserStatePublisher.refreshIfReady(listOf(event.projectId))
        }
    }

    private companion object {
        val BOARD_FIELDS = listOf("boardRead", "boardWrite")
        val GRANT_FIELDS = listOf("read", "write", "manage", "reorder", "readPrivate") + BOARD_FIELDS
    }
}

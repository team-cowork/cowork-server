package com.cowork.channel.global.consumer

import com.cowork.channel.domain.project.entity.ProjectAccessProjection
import com.cowork.channel.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import com.cowork.channel.global.projection.toProjectionPrecision
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class ProjectAccessStateConsumer(
    private val repository: ProjectAccessProjectionRepository,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ProjectAccessStateConsumer::class.java)

    @KafkaListener(
        topics = [Topics.PREFERENCE_PROJECT_ACCESS_STATE],
        groupId = "cowork-channel.project-access-projection",
        containerFactory = "projectAccessStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = streams.projectAccess
        if (processor.processControlRecord(stream, record)) return
        val event = runCatching { parse(record) }.getOrElse {
            log.warn(
                "Quarantine invalid project access state [partition={}, offset={}, reason={}]",
                record.partition(),
                record.offset(),
                it.message,
            )
            processor.quarantineRecord(stream, record, "project access state 계약 위반: ${it.message}")
            return
        }
        processor.applyRecord(stream, record) {
            val existing = repository.findById(event.accessKey).orElse(null)
            if (existing == null) {
                repository.save(event)
            } else if (existing.merge(event)) {
                repository.save(existing)
            }
        }
    }

    private fun parse(record: ConsumerRecord<String, String>): ProjectAccessProjection {
        val node = objectMapper.readTree(record.value())
        require(node.path("eventType").asString() == "UPSERT") { "eventType은 UPSERT여야 합니다." }
        require(node.path("projectId").isIntegralNumber && node.path("accountId").isIntegralNumber) {
            "projectId와 accountId는 정수여야 합니다."
        }
        val projectId = node.path("projectId").asLong()
        val userId = node.path("accountId").asLong()
        require(projectId > 0 && userId > 0) { "projectId와 accountId는 양수여야 합니다." }
        require(record.key() == ProjectAccessProjection.key(projectId, userId)) { "aggregate key가 일치하지 않습니다." }
        require(node.path("read").isBoolean) { "read는 boolean이어야 합니다." }
        val membershipVersion = Instant.parse(node.path("sourceMembershipVersion").asString())
        require(node.has("sourceAuthorizationVersion")) { "sourceAuthorizationVersion이 필요합니다." }
        val authorizationVersion = if (node.hasNonNull("sourceAuthorizationVersion")) {
            Instant.parse(node.path("sourceAuthorizationVersion").asString()).also {
                require(it.isAfter(Instant.EPOCH) && !it.isAfter(membershipVersion)) {
                    "sourceAuthorizationVersion 범위가 올바르지 않습니다."
                }
            }.toProjectionPrecision()
        } else {
            null
        }
        val read = node.path("read").asBoolean()
        val channelArchive = channelArchive(node)
        require(authorizationVersion != null || (!read && !channelArchive)) {
            "권한 버전이 없는 기록은 권한을 허용할 수 없습니다."
        }
        return ProjectAccessProjection(
            accessKey = record.key(),
            projectId = projectId,
            userId = userId,
            canRead = read,
            canChannelArchive = channelArchive,
            sourceOccurredAt = Instant.parse(node.path("occurredAt").asString()).toProjectionPrecision(),
            sourceMembershipVersion = membershipVersion.toProjectionPrecision(),
            sourceAuthorizationVersion = authorizationVersion,
        )
    }

    /** 필드 추가 이전에 발행된 기록은 보관 권한이 없는 것으로 본다. 시작 snapshot이 새 필드로 다시 발행한다. */
    private fun channelArchive(node: JsonNode): Boolean {
        if (!node.has("channelArchive")) return false
        require(node.path("channelArchive").isBoolean) { "channelArchive는 boolean이어야 합니다." }
        return node.path("channelArchive").asBoolean()
    }
}

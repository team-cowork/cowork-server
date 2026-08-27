package com.cowork.project.global.projection

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class ProjectionRecordProcessor(
    private val checkpointStore: ProjectionCheckpointStore,
    private val objectMapper: ObjectMapper,
    private val topicGenerations: ProjectionTopicGenerationRegistry,
    private val readinessState: ProjectionReadinessState,
) {

    @Transactional
    fun processControlRecord(stream: ProjectionStream, record: ConsumerRecord<String, String>): Boolean {
        if (!record.key().orEmpty().startsWith(ProjectionSnapshotCompletion.KEY_PREFIX)) return false

        val topicId = requireTopicId(stream)
        val violation = snapshotCompletionViolation(stream, record)
        if (violation != null) {
            checkpointStore.quarantine(stream, record, violation)
            markStateGap(stream, record, topicId)
        } else {
            checkpointStore.markSnapshotCompleted(
                stream,
                record.partition(),
                record.offset(),
                topicId,
                snapshotId(record),
            )
        }
        checkpointStore.advance(stream, record.partition(), record.offset() + 1, topicId)
        return true
    }

    @Transactional
    fun applyRecord(stream: ProjectionStream, record: ConsumerRecord<String, String>, applyProjection: () -> Unit) {
        applyProjection()
        checkpointStore.advance(stream, record.partition(), record.offset() + 1, requireTopicId(stream))
    }

    @Transactional
    fun quarantineRecord(stream: ProjectionStream, record: ConsumerRecord<String, String>, reason: String) {
        val topicId = requireTopicId(stream)
        checkpointStore.quarantine(stream, record, reason)
        markStateGap(stream, record, topicId)
        checkpointStore.advance(stream, record.partition(), record.offset() + 1, topicId)
    }

    private fun markStateGap(stream: ProjectionStream, record: ConsumerRecord<String, String>, topicId: String) {
        if (stream.expectedSource != null) {
            readinessState.markNotReady(stream)
            checkpointStore.markInvalidRecord(stream, record.partition(), record.offset(), topicId)
        }
    }

    private fun requireTopicId(stream: ProjectionStream): String = checkNotNull(topicGenerations.topicId(stream)) {
        "Kafka projection topic generation이 assignment 전에 초기화되지 않았습니다: ${stream.topic}"
    }

    private fun snapshotId(record: ConsumerRecord<String, String>): String =
        UUID.fromString(objectMapper.readTree(record.value()).path("snapshotId").asText()).toString()

    private fun snapshotCompletionViolation(
        stream: ProjectionStream,
        record: ConsumerRecord<String, String>,
    ): String? {
        if (record.key() != ProjectionSnapshotCompletion.key(record.partition())) {
            return "projection snapshot marker key와 partition이 일치하지 않습니다."
        }
        val payload = runCatching { objectMapper.readTree(record.value()) }
            .getOrElse { return "projection snapshot marker JSON 역직렬화 실패: ${it.message}" }
        if (payload.path("eventType").asText() != ProjectionSnapshotCompletion.EVENT_TYPE) {
            return "projection snapshot marker eventType이 유효하지 않습니다."
        }
        if (payload.path("topic").asText() != record.topic()) {
            return "projection snapshot marker topic이 record topic과 일치하지 않습니다."
        }
        if (!payload.path("partition").canConvertToInt() || payload.path("partition").asInt() != record.partition()) {
            return "projection snapshot marker partition이 record partition과 일치하지 않습니다."
        }
        if (runCatching { UUID.fromString(payload.path("snapshotId").asText()) }.isFailure) {
            return "projection snapshot marker snapshotId가 유효하지 않습니다."
        }
        if (runCatching { Instant.parse(payload.path("occurredAt").asText()) }.isFailure) {
            return "projection snapshot marker occurredAt이 유효하지 않습니다."
        }
        val expectedSource = stream.expectedSource
            ?: return "snapshot-backed state stream이 아닌 topic에는 completion marker를 사용할 수 없습니다."
        if (payload.path("source").asText() != expectedSource) {
            return "projection snapshot marker source가 예상 producer와 일치하지 않습니다: $expectedSource"
        }
        return null
    }
}

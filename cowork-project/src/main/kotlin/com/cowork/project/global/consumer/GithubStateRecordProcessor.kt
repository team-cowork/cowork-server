package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubSyncCompletedMarker
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStream
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * GitHub state 토픽 레코드를 projection 체크포인트와 같은 트랜잭션에 반영한다. 해석할 수 없는 레코드는
 * 격리하고 그 레코드가 속한 대상의 완료 마커를 지운다 — 조회는 `503`으로 바뀌고, 스케줄러가 다시 동기화해
 * 새 완료 마커가 남으면 빠진 상태가 채워진 것으로 본다.
 */
@Component
class GithubStateRecordProcessor(
    private val processor: ProjectionRecordProcessor,
    private val syncMarkers: GithubSyncMarkers,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun <T : Any> process(
        stream: ProjectionStream,
        syncStream: GithubSyncStream,
        record: ConsumerRecord<String, String>,
        type: Class<T>,
        apply: (T) -> Unit,
    ) {
        val node = runCatching { objectMapper.readTree(record.value()) }.getOrNull()
        if (node == null || !node.isObject) {
            quarantine(stream, syncStream, record, null, "JSON 객체가 아닙니다.")
            return
        }
        if (node.text("owner") == null) {
            return quarantine(stream, syncStream, record, node, "owner가 필요합니다.")
        }

        if (syncMarkers.isMarker(node)) {
            val marker = runCatching { objectMapper.treeToValue(node, GithubSyncCompletedMarker::class.java) }
                .getOrElse { return quarantine(stream, syncStream, record, node, "완료 마커 역직렬화 실패: ${it.message}") }
            processor.applyRecord(stream, record) { syncMarkers.record(syncStream, marker) }
            return
        }

        if (node.text("repo") == null) {
            return quarantine(stream, syncStream, record, node, "repo가 필요합니다.")
        }
        val event = runCatching { objectMapper.treeToValue(node, type) }
            .getOrElse { return quarantine(stream, syncStream, record, node, "state 역직렬화 실패: ${it.message}") }
        processor.applyRecord(stream, record) { apply(event) }
    }

    private fun quarantine(
        stream: ProjectionStream,
        syncStream: GithubSyncStream,
        record: ConsumerRecord<String, String>,
        node: JsonNode?,
        reason: String,
    ) {
        log.warn(
            "Quarantine GitHub state record [topic={}, partition={}, offset={}, reason={}]",
            record.topic(),
            record.partition(),
            record.offset(),
            reason,
        )
        syncMarkers.invalidate(syncStream, node?.text("owner"), node?.text("repo"))
        processor.quarantineRecord(stream, record, reason)
    }

    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asString()?.takeIf {
        it.isNotBlank()
    }
}

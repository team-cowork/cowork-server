package com.cowork.project.global.consumer

import com.cowork.project.domain.github.presentation.data.response.GithubTimelineActivityResDto
import com.cowork.project.domain.github.repository.GithubTimelineParent
import com.cowork.project.domain.github.repository.GithubTimelineRepository
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

data class GithubTimelineRecord(
    val subject: String,
    val snapshotId: UUID,
    val item: GithubTimelineActivityResDto? = null,
    val start: Boolean = false,
    val parent: GithubTimelineParent? = null,
    val count: Int? = null,
    val completedAt: Instant? = null,
) {
    fun matches(key: String): Boolean {
        if (!key.startsWith("$subject:") || subject.isBlank() || subject.length > 255) return false
        if (start) {
            return key == "$subject:start" && item == null && count == null && parent == null &&
                completedAt == null
        }
        return if (item == null) {
            key == "$subject:complete" && count != null && count >= 0 && completedAt != null && parent != null &&
                (parent != GithubTimelineParent.MISSING || count == 0)
        } else {
            key == "$subject:${item.id}" &&
                parent == null && count == null && completedAt == null && item.id.length in 1..255 &&
                item.type.isNotBlank()
        }
    }
}

@Component
class GithubTimelineStateConsumer(
    private val githubTimelineRepository: GithubTimelineRepository,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [TOPIC], groupId = GROUP, containerFactory = "githubTimelineStateListenerContainerFactory")
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = projectionStreams.githubTimelineState
        val key = record.key().orEmpty()
        val event = runCatching { objectMapper.readValue<GithubTimelineRecord>(record.value()) }.getOrNull()
        if (event == null || !event.matches(key)) {
            githubTimelineRepository.invalidate(key.substringBefore(':'))
            projectionRecordProcessor.quarantineRecord(stream, record, "Invalid timeline record")
            return
        }
        projectionRecordProcessor.applyRecord(stream, record) {
            if (event.start) {
                githubTimelineRepository.discardUnpublished(event.subject, event.snapshotId)
            } else if (event.item == null) {
                githubTimelineRepository.complete(
                    event.subject,
                    event.snapshotId,
                    checkNotNull(event.parent),
                    checkNotNull(event.count),
                    checkNotNull(event.completedAt),
                )
            } else {
                githubTimelineRepository.stage(event.subject, event.snapshotId, event.item)
            }
        }
    }

    companion object {
        const val TOPIC = "github.timeline.state.v1"
        const val GROUP = "cowork-project.github-timeline-state"
    }
}

package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.githubBoard.entity.GithubProjectItemProjection
import com.cowork.project.domain.githubBoard.event.GITHUB_PROJECT_ITEM_STATE_TOPIC
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.toProjectionPrecision
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Project item state를 반영한다. 삭제 웹훅으로 만든 레코드에는 Project 번호가 없어 대상 없이도 받는다.
 * Projection은 카드 이동 결과가 아니라 이 레코드로만 바뀌므로, 동시에 같은 카드를 옮기면 GitHub에 마지막으로 반영된 값이 남는다.
 */
@Component
class GithubProjectItemStateConsumer(
    private val githubProjectItemProjectionRepository: GithubProjectItemProjectionRepository,
    private val githubStateRecordProcessor: GithubStateRecordProcessor,
    private val projectionStreams: ProjectionStreams,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [GITHUB_PROJECT_ITEM_STATE_TOPIC],
        groupId = "cowork-project.github-project-item-state",
        containerFactory = "githubProjectItemStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        githubStateRecordProcessor.process(
            projectionStreams.githubProjectItemState,
            GithubSyncStream.PROJECT_ITEM,
            record,
            GithubProjectItemStateEvent::class.java,
            scopeOf = ::projectScope,
            requireScope = false,
        ) { event -> apply(event) }
    }

    private fun apply(event: GithubProjectItemStateEvent) {
        if (event.itemId.isBlank() || event.projectNodeId.isBlank()) {
            log.warn("Skip GitHub project item state without item or project id [owner={}]", event.owner)
            return
        }
        val projection = githubProjectItemProjectionRepository.findById(event.itemId).orElse(null)
        if (projection != null && !projection.accepts(event.sourceOccurredAt, event.deleted)) return

        val payload = objectMapper.writeValueAsString(event)
        val version = event.sourceOccurredAt.toProjectionPrecision()
        if (projection == null) {
            githubProjectItemProjectionRepository.save(
                GithubProjectItemProjection(
                    id = event.itemId,
                    projectNodeId = event.projectNodeId,
                    owner = event.owner.lowercase(),
                    payloadJson = payload,
                    archived = event.archived,
                    deleted = event.deleted,
                    sourceOccurredAt = version,
                ),
            )
            return
        }
        projection.projectNodeId = event.projectNodeId
        projection.owner = event.owner.lowercase()
        projection.payloadJson = payload
        projection.archived = event.archived
        projection.deleted = event.deleted
        projection.sourceOccurredAt = version
        githubProjectItemProjectionRepository.save(projection)
    }

    private companion object {
        fun projectScope(node: JsonNode): String? = node.path("projectNumber")
            .takeIf { it.isIntegralNumber && it.asInt() > 0 }
            ?.let { GithubSyncMarker.projectScope(it.asInt()) }
    }
}

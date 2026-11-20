package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.githubBoard.entity.GithubProjectProjection
import com.cowork.project.domain.githubBoard.event.GITHUB_PROJECT_STATE_TOPIC
import com.cowork.project.domain.githubBoard.event.GithubProjectStateEvent
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.toProjectionPrecision
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class GithubProjectStateConsumer(
    private val githubProjectProjectionRepository: GithubProjectProjectionRepository,
    private val githubStateRecordProcessor: GithubStateRecordProcessor,
    private val projectionStreams: ProjectionStreams,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_PROJECT_STATE_TOPIC],
        groupId = "cowork-project.github-project-state",
        containerFactory = "githubProjectStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        githubStateRecordProcessor.process(
            projectionStreams.githubProjectState,
            GithubSyncStream.PROJECT,
            record,
            GithubProjectStateEvent::class.java,
            scopeOf = ::projectScope,
        ) { event -> apply(event) }
    }

    private fun apply(event: GithubProjectStateEvent) {
        val id = GithubProjectProjection.id(event.owner, event.projectNumber)
        val projection = githubProjectProjectionRepository.findById(id).orElseGet {
            GithubProjectProjection(id = id, owner = event.owner.lowercase(), projectNumber = event.projectNumber)
        }
        if (!projection.accepts(event.sourceOccurredAt, event.deleted)) return

        projection.nodeId = event.nodeId
        projection.title = event.title
        projection.url = event.url
        projection.closed = event.closed
        projection.accessError = event.accessError
        projection.fieldsJson = objectMapper.writeValueAsString(event.fields)
        projection.viewsJson = objectMapper.writeValueAsString(event.views)
        projection.itemIdsJson = objectMapper.writeValueAsString(event.itemIds)
        projection.deleted = event.deleted
        projection.sourceOccurredAt = event.sourceOccurredAt.toProjectionPrecision()
        githubProjectProjectionRepository.save(projection)
    }

    private companion object {
        fun projectScope(node: JsonNode): String? = node.path("projectNumber")
            .takeIf { it.isIntegralNumber && it.asInt() > 0 }
            ?.let { GithubSyncMarker.projectScope(it.asInt()) }
    }
}

package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_REPO_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubRepoStateEvent
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class GithubRepoStateConsumer(
    private val repository: GithubRepoProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val syncMarkers: GithubSyncMarkers,
) {
    @KafkaListener(
        topics = [GITHUB_REPO_STATE_TOPIC],
        groupId = "cowork-project.github-repo-state",
        containerFactory = "githubRepoStateListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        if (syncMarkers.recordIfMarker(GithubSyncStream.REPO, record.value())) return
        val event = objectMapper.readValue(record.value(), GithubRepoStateEvent::class.java)
        val id = GithubRepoProjection.id(event.owner, event.repo)
        val projection = repository.findById(id).orElseGet {
            GithubRepoProjection(
                id = id,
                installationId = event.installationId,
                owner = event.owner,
                repo = event.repo,
                fullName = event.fullName,
                private = event.private,
                htmlUrl = event.htmlUrl,
                sourceOccurredAt = Instant.EPOCH,
            )
        }
        if (projection.apply(event)) {
            repository.save(projection)
        }
    }
}

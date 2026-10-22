package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_PULL_REQUEST_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubPullRequestStateEvent
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class GithubPullRequestStateConsumer(
    private val repository: GithubPullRequestProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val syncMarkers: GithubSyncMarkers,
) {
    @KafkaListener(
        topics = [GITHUB_PULL_REQUEST_STATE_TOPIC],
        groupId = "cowork-project.github-pull-request-state",
        containerFactory = "githubPullRequestStateListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        if (syncMarkers.recordIfMarker(GithubSyncStream.PULL_REQUEST, record.value())) return
        val event = objectMapper.readValue(record.value(), GithubPullRequestStateEvent::class.java)
        val id = GithubPullRequestProjection.id(event.owner, event.repo, event.number)
        val projection = repository.findById(id).orElseGet {
            GithubPullRequestProjection(
                id = id,
                owner = event.owner,
                repo = event.repo,
                number = event.number,
                title = event.title,
                author = event.author,
                state = event.state,
                draft = event.draft,
                merged = event.merged,
                htmlUrl = event.htmlUrl,
                headRef = event.headRef,
                baseRef = event.baseRef,
                githubCreatedAt = event.createdAt,
                githubUpdatedAt = event.updatedAt,
                sourceOccurredAt = Instant.EPOCH,
            )
        }
        if (projection.apply(event)) {
            repository.save(projection)
        }
    }
}

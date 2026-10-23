package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_REPO_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubRepoStateEvent
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.service.GithubSyncMarkers
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GithubRepoStateConsumer(
    private val repository: GithubRepoProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
    private val processor: GithubStateRecordProcessor,
    private val streams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_REPO_STATE_TOPIC],
        groupId = "cowork-project.github-repo-state",
        containerFactory = "githubRepoStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        processor.process(
            streams.githubRepoState,
            GithubSyncStream.REPO,
            record,
            GithubRepoStateEvent::class.java,
        ) { event ->
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
                if (event.deleted) syncMarkers.clearRepo(event.owner, event.repo)
            }
        }
    }
}

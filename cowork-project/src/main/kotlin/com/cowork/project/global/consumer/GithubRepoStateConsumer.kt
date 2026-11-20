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
    private val githubRepoProjectionRepository: GithubRepoProjectionRepository,
    private val githubSyncMarkers: GithubSyncMarkers,
    private val githubStateRecordProcessor: GithubStateRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_REPO_STATE_TOPIC],
        groupId = "cowork-project.github-repo-state",
        containerFactory = "githubRepoStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        githubStateRecordProcessor.process(
            projectionStreams.githubRepoState,
            GithubSyncStream.REPO,
            record,
            GithubRepoStateEvent::class.java,
        ) { event ->
            val id = GithubRepoProjection.id(event.owner, event.repo)
            val projection = githubRepoProjectionRepository.findById(id).orElseGet {
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
                githubRepoProjectionRepository.save(projection)
                if (event.deleted) githubSyncMarkers.clearRepo(event.owner, event.repo)
            }
        }
    }
}

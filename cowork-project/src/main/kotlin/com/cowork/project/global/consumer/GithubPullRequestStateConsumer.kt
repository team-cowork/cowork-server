package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_PULL_REQUEST_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubPullRequestStateEvent
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GithubPullRequestStateConsumer(
    private val githubPullRequestProjectionRepository: GithubPullRequestProjectionRepository,
    private val githubStateRecordProcessor: GithubStateRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_PULL_REQUEST_STATE_TOPIC],
        groupId = "cowork-project.github-pull-request-state",
        containerFactory = "githubPullRequestStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        githubStateRecordProcessor.process(
            projectionStreams.githubPullRequestState,
            GithubSyncStream.PULL_REQUEST,
            record,
            GithubPullRequestStateEvent::class.java,
        ) { event ->
            val id = GithubPullRequestProjection.id(event.owner, event.repo, event.number)
            val projection = githubPullRequestProjectionRepository.findById(id).orElseGet {
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
                githubPullRequestProjectionRepository.save(projection)
            }
        }
    }
}

package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubIssueProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_ISSUE_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubIssueStateEvent
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GithubIssueStateConsumer(
    private val repository: GithubIssueProjectionRepository,
    private val processor: GithubStateRecordProcessor,
    private val streams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_ISSUE_STATE_TOPIC],
        groupId = "cowork-project.github-issue-state",
        containerFactory = "githubIssueStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        processor.process(
            streams.githubIssueState,
            GithubSyncStream.ISSUE,
            record,
            GithubIssueStateEvent::class.java,
        ) { event ->
            val id = GithubIssueProjection.id(event.owner, event.repo, event.number)
            val projection = repository.findById(id).orElseGet {
                GithubIssueProjection(
                    id = id,
                    owner = event.owner,
                    repo = event.repo,
                    number = event.number,
                    title = event.title,
                    author = event.author,
                    state = event.state,
                    htmlUrl = event.htmlUrl,
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
}

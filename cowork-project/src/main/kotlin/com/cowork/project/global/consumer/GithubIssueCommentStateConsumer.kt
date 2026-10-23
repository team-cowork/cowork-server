package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubIssueCommentProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_ISSUE_COMMENT_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubIssueCommentStateEvent
import com.cowork.project.domain.github.repository.GithubIssueCommentProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GithubIssueCommentStateConsumer(
    private val repository: GithubIssueCommentProjectionRepository,
    private val processor: GithubStateRecordProcessor,
    private val streams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_ISSUE_COMMENT_STATE_TOPIC],
        groupId = "cowork-project.github-issue-comment-state",
        containerFactory = "githubIssueCommentStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        processor.process(
            streams.githubIssueCommentState,
            GithubSyncStream.ISSUE_COMMENT,
            record,
            GithubIssueCommentStateEvent::class.java,
        ) { event ->
            val id = GithubIssueCommentProjection.id(event.owner, event.repo, event.commentId)
            val projection = repository.findById(id).orElseGet {
                GithubIssueCommentProjection(
                    id = id,
                    owner = event.owner,
                    repo = event.repo,
                    commentId = event.commentId,
                    parentType = event.parentType,
                    parentNumber = event.parentNumber,
                    author = event.author,
                    body = event.body,
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

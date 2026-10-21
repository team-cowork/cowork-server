package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubIssueProjection
import com.cowork.project.domain.github.event.GITHUB_ISSUE_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubIssueStateEvent
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class GithubIssueStateConsumer(
    private val repository: GithubIssueProjectionRepository,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_ISSUE_STATE_TOPIC],
        groupId = "cowork-project.github-issue-state",
        containerFactory = "githubIssueStateListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), GithubIssueStateEvent::class.java)
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

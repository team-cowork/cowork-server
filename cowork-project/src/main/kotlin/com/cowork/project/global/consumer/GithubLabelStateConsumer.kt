package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubLabelProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_LABEL_STATE_TOPIC
import com.cowork.project.domain.github.event.GithubLabelStateEvent
import com.cowork.project.domain.github.repository.GithubLabelProjectionRepository
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GithubLabelStateConsumer(
    private val githubLabelProjectionRepository: GithubLabelProjectionRepository,
    private val githubStateRecordProcessor: GithubStateRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_LABEL_STATE_TOPIC],
        groupId = "cowork-project.github-label-state",
        containerFactory = "githubLabelStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        githubStateRecordProcessor.process(
            projectionStreams.githubLabelState,
            GithubSyncStream.LABEL,
            record,
            GithubLabelStateEvent::class.java,
        ) { event ->
            val id = GithubLabelProjection.id(event.owner, event.repo, event.name)
            val projection = githubLabelProjectionRepository.findById(id).orElseGet {
                GithubLabelProjection(
                    id = id,
                    owner = event.owner,
                    repo = event.repo,
                    name = event.name,
                    color = event.color,
                    sourceOccurredAt = Instant.EPOCH,
                )
            }
            if (projection.apply(event)) {
                githubLabelProjectionRepository.save(projection)
            }
        }
    }
}

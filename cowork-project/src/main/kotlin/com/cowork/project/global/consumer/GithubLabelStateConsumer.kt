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
    private val repository: GithubLabelProjectionRepository,
    private val processor: GithubStateRecordProcessor,
    private val streams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [GITHUB_LABEL_STATE_TOPIC],
        groupId = "cowork-project.github-label-state",
        containerFactory = "githubLabelStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        processor.process(
            streams.githubLabelState,
            GithubSyncStream.LABEL,
            record,
            GithubLabelStateEvent::class.java,
        ) { event ->
            val id = GithubLabelProjection.id(event.owner, event.repo, event.name)
            val projection = repository.findById(id).orElseGet {
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
                repository.save(projection)
            }
        }
    }
}

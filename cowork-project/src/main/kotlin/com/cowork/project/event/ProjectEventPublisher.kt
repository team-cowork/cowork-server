package com.cowork.project.event

import com.cowork.project.domain.Project
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private const val TOPIC = "project.event"

@Component
class ProjectEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(ProjectEventPublisher::class.java)

    fun publishCreated(project: Project) = publish("CREATED", project)
    fun publishUpdated(project: Project) = publish("UPDATED", project)
    fun publishDeleted(project: Project) = publish("DELETED", project)

    private fun publish(eventType: String, project: Project) {
        val event = ProjectEvent(
            eventType = eventType,
            projectId = project.id,
            teamId = project.teamId,
            name = project.name,
            description = project.description,
            status = project.status.name,
        )
        kafkaTemplate.send(TOPIC, project.teamId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "프로젝트 이벤트 발행 실패 [eventType={}, projectId={}]",
                        eventType, project.id, ex,
                    )
                } else {
                    log.info(
                        "프로젝트 이벤트 발행 성공 [eventType={}, projectId={}, offset={}]",
                        eventType, project.id, result.recordMetadata.offset(),
                    )
                }
            }
    }
}

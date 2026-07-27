package com.cowork.project.domain.projectMember.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private const val TOPIC = "project.member.event"

@Component
class ProjectMemberEventPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    private val log = LoggerFactory.getLogger(ProjectMemberEventPublisher::class.java)

    fun publishAdded(projectId: Long, userId: Long) = publish(ProjectMemberEventType.ADDED, projectId, userId)
    fun publishRemoved(projectId: Long, userId: Long) = publish(ProjectMemberEventType.REMOVED, projectId, userId)

    private fun publish(eventType: ProjectMemberEventType, projectId: Long, userId: Long) {
        val event = ProjectMemberEvent(eventType, projectId, userId)
        kafkaTemplate.send(TOPIC, projectId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "프로젝트 멤버 이벤트 발행 실패 [eventType={}, projectId={}, userId={}]",
                        eventType,
                        projectId,
                        userId,
                        ex,
                    )
                } else {
                    log.info(
                        "프로젝트 멤버 이벤트 발행 성공 [eventType={}, projectId={}, userId={}, offset={}]",
                        eventType,
                        projectId,
                        userId,
                        result.recordMetadata.offset(),
                    )
                }
            }
    }
}

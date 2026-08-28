package com.cowork.project.domain.github.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private const val TOPIC = "notification.trigger"
private const val GITHUB_COMMENT_CREATED = "GITHUB_COMMENT_CREATED"

@Component
class GithubCommentNotificationPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    private val log = LoggerFactory.getLogger(GithubCommentNotificationPublisher::class.java)

    fun publishCommentCreated(targetUserId: Long, data: Map<String, Any?>) {
        val event = NotificationTriggerEvent(
            type = GITHUB_COMMENT_CREATED,
            targetUserIds = listOf(targetUserId),
            data = data,
        )
        kafkaTemplate.send(TOPIC, targetUserId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("GitHub 댓글 알림 발행 실패 [targetUserId={}]", targetUserId, ex)
                } else {
                    log.info(
                        "GitHub 댓글 알림 발행 성공 [targetUserId={}, offset={}]",
                        targetUserId,
                        result.recordMetadata.offset(),
                    )
                }
            }
    }
}

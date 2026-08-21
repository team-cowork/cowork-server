package com.cowork.team.global.consumer

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `cowork-project` 등 다른 서비스가 구독하는 `team.github.disconnected`를 발행한다.
 * `KafkaConsumerConfig`가 만든 범용 `KafkaTemplate<String, Any>`(`teamGithubDlqKafkaTemplate`)를 재사용한다.
 */
@Component
class TeamGithubEventPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    private val log = LoggerFactory.getLogger(TeamGithubEventPublisher::class.java)

    fun publishDisconnected(installationId: Long) {
        val payload = TeamGithubDisconnectedPayload(installationId)
        kafkaTemplate.send(Topics.TEAM_GITHUB_DISCONNECTED, installationId.toString(), payload)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("team.github.disconnected 발행 실패 [installationId={}]", installationId, ex)
                }
            }
    }
}

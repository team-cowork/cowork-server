package com.cowork.project.domain.github.event

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.TimeUnit

const val GITHUB_SYNC_COMMAND_TOPIC = "github.sync.command.v1"

private const val SEND_TIMEOUT_SECONDS = 5L

/**
 * `cowork-github`에 동기화를 요청한다. `ORG_REPOSITORIES`는 org의 repo 목록을, `REPOSITORY`는 repo 하나의
 * 이슈·PR·라벨·댓글을 다시 publish하게 하며, 완료되면 각 state 토픽에 완료 마커가 남는다.
 */
data class GithubSyncCommand(
    val schemaVersion: Int = 1,
    val target: String,
    val owner: String,
    val repo: String?,
    val installationId: Long?,
    val requestedAt: Instant,
)

@Component
class GithubSyncCommandPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    fun publish(command: GithubSyncCommand) {
        val key = command.repo?.let { "${command.owner}/$it" } ?: command.owner
        kafkaTemplate.send(GITHUB_SYNC_COMMAND_TOPIC, key, command).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}

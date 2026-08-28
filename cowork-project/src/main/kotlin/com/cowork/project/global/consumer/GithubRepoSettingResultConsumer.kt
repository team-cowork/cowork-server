package com.cowork.project.global.consumer

import com.cowork.project.domain.githubPreference.event.GITHUB_REPO_SETTING_RESULT_TOPIC
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingResult
import com.cowork.project.domain.githubPreference.service.GithubRepoSettingResultHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class GithubRepoSettingResultConsumer(
    private val handler: GithubRepoSettingResultHandler,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_REPO_SETTING_RESULT_TOPIC],
        groupId = "cowork-project.github-repo-setting-result",
        containerFactory = "githubRepoSettingResultListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), GithubRepoSettingResult::class.java))
        }.getOrElse { throw IllegalArgumentException("GitHub 저장소 설정 result JSON이 유효하지 않습니다.", it) }
        validate(record.key(), payload)
        handler.apply(payload)
    }

    internal fun validate(messageKey: String?, result: GithubRepoSettingResult) {
        require(result.schemaVersion == 1) { "지원하지 않는 schemaVersion입니다." }
        require(runCatching { UUID.fromString(result.operationId) }.isSuccess) { "operationId가 UUID가 아닙니다." }
        require(messageKey == result.operationId) { "operationId와 Kafka key가 일치하지 않습니다." }
        require(result.idempotencyKey.isNotBlank() && result.idempotencyKey.length <= 128) {
            "idempotencyKey가 유효하지 않습니다."
        }
        require(result.repoId > 0) { "repoId는 양수여야 합니다." }
        requireNotNull(result.occurredAt) { "occurredAt이 필요합니다." }
        when (result.status) {
            "SUCCEEDED" -> {
                requireNotNull(result.settings) { "SUCCEEDED에는 settings가 필요합니다." }
                require(result.error == null) { "SUCCEEDED에는 error를 포함할 수 없습니다." }
                requireNotNull(result.stateOccurredAt) { "SUCCEEDED에는 stateOccurredAt이 필요합니다." }
            }
            "FAILED" -> {
                require(result.settings == null) { "FAILED에는 settings를 포함할 수 없습니다." }
                require(result.stateOccurredAt == null) { "FAILED에는 stateOccurredAt을 포함할 수 없습니다." }
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                require(error.code.isNotBlank() && error.code.length <= 100) { "error.code가 유효하지 않습니다." }
                require(error.message.isNotBlank() && error.message.length <= 500) {
                    "error.message가 유효하지 않습니다."
                }
            }
            else -> throw IllegalArgumentException("지원하지 않는 result status입니다.")
        }
    }
}

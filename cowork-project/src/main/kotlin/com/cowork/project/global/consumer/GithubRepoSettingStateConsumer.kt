package com.cowork.project.global.consumer

import com.cowork.project.domain.githubPreference.event.GITHUB_REPO_SETTING_STATE_TOPIC
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingState
import com.cowork.project.domain.githubPreference.service.GithubRepoPreferenceProjectionHandler
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class GithubRepoSettingStateConsumer(
    private val handler: GithubRepoPreferenceProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [GITHUB_REPO_SETTING_STATE_TOPIC],
        groupId = "cowork-project.github-repo-setting",
        containerFactory = "githubRepoSettingStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.githubRepoSetting, record)) return
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), GithubRepoSettingState::class.java)) {
                "top-level null은 허용되지 않습니다."
            }
        }.getOrElse {
            quarantine(record, "GitHub 저장소 설정 state JSON 역직렬화 실패: ${it.message}")
            return
        }

        val occurredAt = payload.occurredAt
        val reason = when {
            payload.schemaVersion != 1 -> "지원하지 않는 schemaVersion입니다."
            record.key() != payload.repoId.toString() -> "repoId와 Kafka key가 일치하지 않습니다."
            payload.repoId <= 0 -> "repoId는 양수여야 합니다."
            payload.eventType !in setOf("UPSERT", "DELETE") -> "지원하지 않는 eventType입니다."
            occurredAt == null -> "occurredAt이 필요합니다."
            payload.eventType == "UPSERT" && payload.settings == null -> "UPSERT에는 settings가 필요합니다."
            payload.eventType == "DELETE" && payload.settings != null -> "DELETE에는 settings를 포함할 수 없습니다."
            else -> null
        }
        if (reason != null) {
            quarantine(record, reason)
            return
        }

        processor.applyRecord(streams.githubRepoSetting, record) {
            handler.apply(
                repoId = payload.repoId,
                labelAutoApply = payload.settings?.labelAutoApply ?: true,
                deleted = payload.eventType == "DELETE",
                occurredAt = requireNotNull(occurredAt),
            )
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "preference.github-repo.setting.state를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.githubRepoSetting, record, reason)
    }
}

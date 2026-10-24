package com.cowork.team.global.consumer

import com.cowork.team.global.projection.ProjectionRecordProcessor
import com.cowork.team.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class PreferenceTeamSettingConsumer(
    private val handler: PreferenceTeamSettingProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(PreferenceTeamSettingConsumer::class.java)

    @KafkaListener(
        topics = [Topics.PREFERENCE_TEAM_SETTING_STATE],
        groupId = "\${KAFKA_GROUP_ID_TEAM_SETTING_PREFERENCE:cowork-team.team-setting-projection}",
        containerFactory = "preferenceTeamSettingListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.teamSetting, record)) return
        val event = runCatching { objectMapper.readValue(record.value(), PreferenceTeamSettingEvent::class.java) }
            .getOrElse {
                quarantine(record, "preference team-setting JSON 역직렬화 실패: ${it.message}")
                return
            }
        val reason = contractViolation(event, record.key())
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        processor.applyRecord(streams.teamSetting, record) { handler.handle(event) }
    }

    private fun contractViolation(event: PreferenceTeamSettingEvent, key: String?): String? = when {
        event.eventType != "UPSERT" -> "지원하지 않는 eventType입니다."
        event.schemaVersion != SCHEMA_VERSION -> "지원하지 않는 schemaVersion입니다."
        event.teamId <= 0 -> "teamId는 양수여야 합니다."
        key != event.teamId.toString() -> "team setting aggregate key가 일치하지 않습니다."
        else -> null
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "Quarantined preference team-setting state record partition={} offset={} reason={}",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.teamSetting, record, reason)
    }

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}

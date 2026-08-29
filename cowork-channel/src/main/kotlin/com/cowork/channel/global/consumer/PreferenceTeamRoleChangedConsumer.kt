package com.cowork.channel.global.consumer

import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class PreferenceTeamRoleChangedConsumer(
    private val handler: PreferenceTeamRoleProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(PreferenceTeamRoleChangedConsumer::class.java)

    @KafkaListener(
        topics = [Topics.PREFERENCE_TEAM_ROLE_CHANGED],
        groupId = "cowork-channel.team-role-projection",
        containerFactory = "preferenceTeamRoleChangedListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.teamRole, record)) return
        val event = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), PreferenceTeamRoleChangedEvent::class.java))
        }.getOrElse {
            quarantine(record, "preference team-role JSON 역직렬화 실패: ${it.message}")
            return
        }
        val reason = contractViolation(event, record.key())
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        processor.applyRecord(streams.teamRole, record) { handler.handle(event) }
    }

    private fun contractViolation(event: PreferenceTeamRoleChangedEvent, key: String?): String? {
        if (event.teamId <= 0) return "teamId는 양수여야 합니다."
        return when (event.eventType) {
            "ROLE_UPSERTED" -> when {
                event.roleId == null || event.roleId <= 0 -> "ROLE_UPSERTED에는 유효한 roleId가 필요합니다."
                key != "role:${event.teamId}:${event.roleId}" -> "role aggregate key가 일치하지 않습니다."
                event.name.isNullOrBlank() ||
                    event.colorHex.isNullOrBlank() ||
                    event.priority == null ||
                    event.mentionable == null ||
                    event.permissions == null ->
                    "ROLE_UPSERTED read model이 완전하지 않습니다."
                else -> null
            }
            "ROLE_DELETED" -> when {
                event.roleId == null || event.roleId <= 0 -> "ROLE_DELETED에는 유효한 roleId가 필요합니다."
                key != "role:${event.teamId}:${event.roleId}" -> "role aggregate key가 일치하지 않습니다."
                else -> null
            }
            "ASSIGNMENT_UPSERTED", "ASSIGNMENT_DELETED" -> when {
                event.roleId == null || event.roleId <= 0 || event.accountId == null || event.accountId <= 0 ->
                    "assignment 이벤트에는 유효한 roleId와 accountId가 필요합니다."
                key != "assignment:${event.teamId}:${event.accountId}:${event.roleId}" ->
                    "assignment aggregate key가 일치하지 않습니다."
                else -> null
            }
            "MEMBER_ASSIGNMENTS_DELETED" -> when {
                event.accountId == null || event.accountId <= 0 ->
                    "멤버 tombstone에는 유효한 accountId가 필요합니다."
                key != "member:${event.teamId}:${event.accountId}" -> "member aggregate key가 일치하지 않습니다."
                else -> null
            }
            else -> "지원하지 않는 eventType입니다."
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "preference.team-role.changed를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.teamRole, record, reason)
    }
}

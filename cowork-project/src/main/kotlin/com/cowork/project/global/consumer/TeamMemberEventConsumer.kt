package com.cowork.project.global.consumer

import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

private const val TEAM_MEMBER_EVENT_TOPIC = "team.member.event"

@Component
class TeamMemberEventConsumer(
    private val handler: ProjectLifecycleHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(TeamMemberEventConsumer::class.java)

    @KafkaListener(
        topics = [TEAM_MEMBER_EVENT_TOPIC],
        groupId = "cowork-project.team-member",
        containerFactory = "teamMemberEventListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.teamMember, record)) return
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), TeamMemberEventPayload::class.java)) {
                "top-level null은 허용되지 않습니다."
            }
        }
            .getOrElse {
                quarantine(record, "team.member.event JSON 역직렬화 실패: ${it.message}")
                return
            }
        val expectedKey = "${payload.teamId}:${payload.userId}"
        val occurredAt = payload.occurredAt
        // TODO(#596): 2026-11-01 05:00 KST 이후 구형 레코드 재처리·롤백 지원 종료와 snapshot 보정 완료를 확인한 뒤 authorizationVersion 필수화.
        // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
        val authorizationVersion = payload.authorizationVersion
        val reason = when {
            record.key() != expectedKey -> "teamId:userId와 Kafka key가 일치하지 않습니다."
            payload.teamId <= 0 || payload.userId <= 0 -> "teamId와 userId는 양수여야 합니다."
            occurredAt == null -> "occurredAt이 필요합니다."
            authorizationVersion != null && (!authorizationVersion.isAfter(Instant.EPOCH) || authorizationVersion.isAfter(occurredAt)) ->
                "authorizationVersion은 양수이며 occurredAt 이하여야 합니다."
            payload.role !in setOf("OWNER", "ADMIN", "MEMBER") -> "지원하지 않는 role입니다."
            payload.eventType !in setOf("UPSERT", "DELETE") -> "지원하지 않는 eventType입니다."
            else -> null
        }
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        val eventOccurredAt = requireNotNull(occurredAt)

        processor.applyRecord(streams.teamMember, record) {
            when (payload.eventType) {
                "UPSERT" -> handler.onMemberUpsert(payload.teamId, payload.userId, payload.role, eventOccurredAt, authorizationVersion)
                "DELETE" -> handler.onMemberRemovedFromTeam(
                    payload.teamId,
                    payload.userId,
                    payload.role,
                    eventOccurredAt,
                    authorizationVersion,
                )
            }
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "team.member.event를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.teamMember, record, reason)
    }
}

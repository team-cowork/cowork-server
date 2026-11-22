package com.cowork.team.global.consumer

import com.cowork.team.domain.calendar.model.CalendarSeries
import com.cowork.team.domain.calendar.projection.PROJECT_CALENDAR_EVENT_STATE_TOPIC
import com.cowork.team.domain.calendar.projection.PROJECT_READABLE_USER_STATE_TOPIC
import com.cowork.team.domain.calendar.projection.ProjectCalendarEventStatePayload
import com.cowork.team.domain.calendar.projection.ProjectCalendarProjectionStore
import com.cowork.team.domain.calendar.projection.ProjectReadableUserStatePayload
import com.cowork.team.global.projection.ProjectionRecordProcessor
import com.cowork.team.global.projection.ProjectionStream
import com.cowork.team.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import team.themoment.sdk.logging.logger.logger
import tools.jackson.databind.ObjectMapper

/** cowork-project의 프로젝트 일정과 읽기 가능 사용자 상태를 팀 캘린더 projection에 반영한다. */
@Component
class ProjectCalendarStateConsumer(
    private val projectCalendarProjectionStore: ProjectCalendarProjectionStore,
    private val objectMapper: ObjectMapper,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {

    @KafkaListener(
        topics = [PROJECT_CALENDAR_EVENT_STATE_TOPIC],
        groupId = ProjectionStreams.PROJECT_CALENDAR_EVENT_GROUP,
        containerFactory = "projectCalendarEventListenerContainerFactory",
    )
    fun consumeEvent(record: ConsumerRecord<String, String>) {
        val stream = projectionStreams.projectCalendarEvent
        if (projectionRecordProcessor.processControlRecord(stream, record)) return
        val payload = runCatching {
            objectMapper.readValue(record.value(), ProjectCalendarEventStatePayload::class.java)
        }
            .getOrElse { return quarantine(stream, record, "project calendar event JSON 역직렬화 실패: ${it.message}") }
        val series = runCatching { payload.series() }
            .getOrElse { return quarantine(stream, record, "일정 시각, 시간대 또는 반복 규칙이 유효하지 않습니다.") }
        eventViolation(payload, series, record.key())?.let { return quarantine(stream, record, it) }
        projectionRecordProcessor.applyRecord(stream, record) {
            projectCalendarProjectionStore.applyEvent(payload, series)
        }
    }

    @KafkaListener(
        topics = [PROJECT_READABLE_USER_STATE_TOPIC],
        groupId = ProjectionStreams.PROJECT_READABLE_USER_GROUP,
        containerFactory = "projectReadableUserListenerContainerFactory",
    )
    fun consumeReaders(record: ConsumerRecord<String, String>) {
        val stream = projectionStreams.projectReadableUser
        if (projectionRecordProcessor.processControlRecord(stream, record)) return
        val payload = runCatching {
            objectMapper.readValue(record.value(), ProjectReadableUserStatePayload::class.java)
        }
            .getOrElse { return quarantine(stream, record, "project readable user JSON 역직렬화 실패: ${it.message}") }
        readerViolation(payload, record.key())?.let { return quarantine(stream, record, it) }
        projectionRecordProcessor.applyRecord(stream, record) {
            projectCalendarProjectionStore.applyReaders(payload)
        }
    }

    private fun quarantine(stream: ProjectionStream, record: ConsumerRecord<String, String>, reason: String) {
        logger().warn(
            "Quarantined project calendar state record topic={} partition={} offset={} reason={}",
            record.topic(),
            record.partition(),
            record.offset(),
            reason,
        )
        projectionRecordProcessor.quarantineRecord(stream, record, reason)
    }

    private companion object {
        fun eventViolation(payload: ProjectCalendarEventStatePayload, series: CalendarSeries, key: String?): String? =
            when {
                payload.eventId <= 0 || payload.projectId <= 0 || payload.teamId <= 0 -> "ID는 양수여야 합니다."

                key != payload.eventId.toString() -> "calendar event aggregate key가 일치하지 않습니다."

                payload.title.length > 200 || (payload.description?.length ?: 0) > 2000 -> "제목 또는 설명이 너무 깁니다."

                series.exceptions.map { it.originalStartAt }.toSet().size != series.exceptions.size ->
                    "같은 발생분의 예외가 둘 이상입니다."

                else -> null
            }

        fun readerViolation(payload: ProjectReadableUserStatePayload, key: String?): String? = when {
            payload.projectId <= 0 || payload.teamId <= 0 -> "ID는 양수여야 합니다."
            key != payload.projectId.toString() -> "readable user aggregate key가 일치하지 않습니다."
            payload.userIds.any { it <= 0 } -> "사용자 ID는 양수여야 합니다."
            else -> null
        }
    }
}

package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ChannelRolePolicyChangedConsumer(
    private val handler: ChannelRolePolicyProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(ChannelRolePolicyChangedConsumer::class.java)

    @KafkaListener(
        topics = [Topics.CHANNEL_ROLE_POLICY_CHANGED],
        groupId = "cowork-channel.channel-role-policy-projection",
        containerFactory = "channelRolePolicyChangedListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.channelRolePolicy, record)) return
        val event = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), ChannelRolePolicyChangedEvent::class.java))
        }.getOrElse {
            quarantine(record, "channel role policy JSON 역직렬화 실패: ${it.message}")
            return
        }
        val reason = contractViolation(event, record.key())
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        processor.applyRecord(streams.channelRolePolicy, record) { handler.handle(event) }
    }

    private fun contractViolation(event: ChannelRolePolicyChangedEvent, key: String?): String? {
        if (event.schemaVersion != 1) return "지원하지 않는 schemaVersion입니다."
        if (event.teamId <= 0 || event.channelId <= 0 || event.roleId <= 0) {
            return "teamId, channelId, roleId는 양수여야 합니다."
        }
        if (key != ChannelRolePolicyProjection.key(event.teamId, event.channelId, event.roleId)) {
            return "policy aggregate key가 일치하지 않습니다."
        }
        return when (event.eventType) {
            "UPSERT" -> if (
                event.permissions?.keys != setOf(MESSAGE_READ_KEY) ||
                event.permissions[MESSAGE_READ_KEY] == null
            ) {
                "UPSERT permissions에는 message_read boolean 하나만 필요합니다."
            } else {
                null
            }
            "DELETE" -> if (event.permissions != null) "DELETE에는 permissions가 없어야 합니다." else null
            else -> "지원하지 않는 eventType입니다."
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "preference.channel-role-policy.changed를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.channelRolePolicy, record, reason)
    }

    private companion object {
        const val MESSAGE_READ_KEY = "message_read"
    }
}

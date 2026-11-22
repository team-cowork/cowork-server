package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyContractTopics
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import team.themoment.sdk.logging.logger.logger
import tools.jackson.databind.ObjectMapper

@Component
class ChannelRolePolicyChangedConsumer(
    private val channelRolePolicyProjectionHandler: ChannelRolePolicyProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {

    @KafkaListener(
        topics = [Topics.CHANNEL_ROLE_POLICY_CHANGED],
        groupId = "cowork-channel.channel-role-policy-projection",
        containerFactory = "channelRolePolicyChangedListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (projectionRecordProcessor.processControlRecord(projectionStreams.channelRolePolicy, record)) return
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
        projectionRecordProcessor.applyRecord(projectionStreams.channelRolePolicy, record) {
            channelRolePolicyProjectionHandler.handle(event)
        }
    }

    private fun contractViolation(event: ChannelRolePolicyChangedEvent, key: String?): String? {
        if (event.schemaVersion != ChannelRolePolicyContractTopics.SCHEMA_VERSION) return "지원하지 않는 schemaVersion입니다."
        if (event.teamId <= 0 || event.channelId <= 0 || event.roleId <= 0) {
            return "teamId, channelId, roleId는 양수여야 합니다."
        }
        if (key != ChannelRolePolicyProjection.key(event.teamId, event.channelId, event.roleId)) {
            return "policy aggregate key가 일치하지 않습니다."
        }
        return when (event.eventType) {
            "UPSERT" -> when {
                event.permissions.isNullOrEmpty() -> "UPSERT permissions에는 권한이 하나 이상 필요합니다."
                event.permissions.values.any { it == null } -> "UPSERT permissions 값은 boolean이어야 합니다."
                else -> null
            }

            "DELETE" -> if (event.permissions != null) "DELETE에는 permissions가 없어야 합니다." else null

            else -> "지원하지 않는 eventType입니다."
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        logger().warn(
            "Quarantined preference channel-role-policy state record partition={} offset={} reason={}",
            record.partition(),
            record.offset(),
            reason,
        )
        projectionRecordProcessor.quarantineRecord(projectionStreams.channelRolePolicy, record, reason)
    }
}

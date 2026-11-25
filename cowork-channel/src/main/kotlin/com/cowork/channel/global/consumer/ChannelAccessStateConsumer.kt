package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjection
import com.cowork.channel.domain.channelRolePolicy.access.ChannelAccessProjectionRepository
import com.cowork.channel.global.policy.PermissionKeys
import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import com.cowork.channel.global.projection.toProjectionPrecision
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import team.themoment.sdk.logging.logger.logger
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class ChannelAccessStateConsumer(
    private val channelAccessProjectionRepository: ChannelAccessProjectionRepository,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
    private val objectMapper: ObjectMapper,
) {

    @KafkaListener(
        topics = [Topics.PREFERENCE_CHANNEL_ACCESS_STATE],
        groupId = "cowork-channel.channel-access-projection",
        containerFactory = "channelAccessStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = projectionStreams.channelAccess
        if (projectionRecordProcessor.processControlRecord(stream, record)) return
        val event = runCatching { parse(record) }.getOrElse {
            logger().warn(
                "Quarantine invalid channel access state [partition={}, offset={}, reason={}]",
                record.partition(),
                record.offset(),
                it.message,
            )
            projectionRecordProcessor.quarantineRecord(stream, record, "channel access state 계약 위반: ${it.message}")
            return
        }
        projectionRecordProcessor.applyRecord(stream, record) {
            val existing = channelAccessProjectionRepository.findById(event.accessKey).orElse(null)
            if (existing == null) {
                channelAccessProjectionRepository.save(event)
            } else if (existing.merge(event)) {
                channelAccessProjectionRepository.save(existing)
            }
        }
    }

    internal fun parse(record: ConsumerRecord<String, String>): ChannelAccessProjection {
        val node = objectMapper.readTree(record.value())
        require(node.path("eventType").asString() == "UPSERT") { "eventType은 UPSERT여야 합니다." }
        require(
            node.path("channelId").isIntegralNumber &&
                node.path("accountId").isIntegralNumber &&
                node.path("teamId").isIntegralNumber,
        ) {
            "channelId, accountId, teamId는 정수여야 합니다."
        }
        val channelId = node.path("channelId").asLong()
        val userId = node.path("accountId").asLong()
        val teamId = node.path("teamId").asLong()
        require(channelId > 0 && userId > 0 && teamId > 0) { "channelId, accountId, teamId는 양수여야 합니다." }
        require(record.key() == ChannelAccessProjection.key(channelId, userId)) { "aggregate key가 일치하지 않습니다." }
        val permissionsNode = node.path("permissions")
        require(permissionsNode.isObject) { "permissions는 객체여야 합니다." }
        // Preference publishes every channel-scoped key, so a missing key means the producer contract changed.
        val allowed = PermissionKeys.CHANNEL_SCOPED.filterTo(linkedSetOf()) { key ->
            val value = permissionsNode.path(key)
            require(value.isBoolean) { "permissions.${key}는 boolean이어야 합니다." }
            value.asBoolean()
        }
        require(node.path("readPrivate").isBoolean) { "readPrivate는 boolean이어야 합니다." }
        val readPrivate = node.path("readPrivate").asBoolean()
        // Preference sends the Unix epoch as the membership version of a member who is not active.
        val membershipVersion = Instant.parse(node.path("sourceMembershipVersion").asString())
        require(!membershipVersion.isBefore(Instant.EPOCH)) { "sourceMembershipVersion 범위가 올바르지 않습니다." }
        require(node.has("sourceAuthorizationVersion")) { "sourceAuthorizationVersion이 필요합니다." }
        val authorizationVersion = if (node.hasNonNull("sourceAuthorizationVersion")) {
            Instant.parse(node.path("sourceAuthorizationVersion").asString()).also {
                require(it.isAfter(Instant.EPOCH) && !it.isAfter(membershipVersion)) {
                    "sourceAuthorizationVersion 범위가 올바르지 않습니다."
                }
            }.toProjectionPrecision()
        } else {
            null
        }
        require(authorizationVersion != null || (allowed.isEmpty() && !readPrivate)) {
            "권한 버전이 없는 기록은 권한을 허용할 수 없습니다."
        }
        return ChannelAccessProjection(
            accessKey = record.key(),
            channelId = channelId,
            userId = userId,
            teamId = teamId,
            allowedPermissions = allowed,
            canReadPrivate = readPrivate,
            sourceOccurredAt = Instant.parse(node.path("occurredAt").asString()).toProjectionPrecision(),
            sourceMembershipVersion = membershipVersion.toProjectionPrecision(),
            sourceAuthorizationVersion = authorizationVersion,
        )
    }
}

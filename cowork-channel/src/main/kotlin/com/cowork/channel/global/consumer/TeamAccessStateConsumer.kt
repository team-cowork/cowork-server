package com.cowork.channel.global.consumer

import com.cowork.channel.global.policy.PermissionKeys
import com.cowork.channel.global.policy.TeamAccessProjection
import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class TeamAccessState(
    val teamId: Long,
    val accountId: Long,
    val allowedPermissions: Set<String>,
    val membershipVersion: Instant,
    val authorizationVersion: Instant?,
    val occurredAt: Instant,
) {
    companion object {
        const val TOPIC = "preference.team-access.state.v1"
        const val GROUP = "cowork-channel.team-access-projection"

        fun parse(key: String?, node: JsonNode): TeamAccessState {
            require(node.path("eventType").asString() == "UPSERT")
            fun id(field: String): Long = node.path(field).let {
                require(it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0)
                it.asLong()
            }
            val teamId = id("teamId")
            val accountId = id("accountId")
            require(key == "$teamId:$accountId")
            val permissions = node.path("permissions")
            require(permissions.isObject && permissions.all { it.isBoolean })
            require(PermissionKeys.TEAM_SCOPED.all { permissions.has(it) })
            val allowed = PermissionKeys.TEAM_SCOPED.filterTo(mutableSetOf()) { permissions.path(it).asBoolean() }
            // 초기 v1 스냅샷에는 채널 및 프로젝트 기본 권한이 없다.
            val baseGrants = node.path("baseGrants")
            if (!baseGrants.isMissingNode) {
                require(baseGrants.isArray && baseGrants.all { it.isString && !permissions.has(it.asString()) })
                baseGrants.asSequence().map { it.asString() }.filterTo(allowed) { it in PermissionKeys.CHANNEL_SCOPED }
            }
            val membership = Instant.parse(node.path("sourceMembershipVersion").asString())
            require(!membership.isBefore(Instant.EPOCH) && node.has("sourceAuthorizationVersion"))
            val authorization = if (node.hasNonNull("sourceAuthorizationVersion")) {
                Instant.parse(node.path("sourceAuthorizationVersion").asString()).also {
                    require(it.isAfter(Instant.EPOCH) && !it.isAfter(membership))
                }
            } else {
                require(permissions.none { it.asBoolean() } && baseGrants.isEmpty)
                null
            }
            return TeamAccessState(
                teamId,
                accountId,
                allowed,
                membership,
                authorization,
                Instant.parse(node.path("occurredAt").asString()),
            )
        }
    }
}

@Component
class TeamAccessStateConsumer(
    private val objectMapper: ObjectMapper,
    private val teamAccessProjection: TeamAccessProjection,
    private val projectionRecordProcessor: ProjectionRecordProcessor,
    private val projectionStreams: ProjectionStreams,
) {
    @KafkaListener(
        topics = [TeamAccessState.TOPIC],
        groupId = TeamAccessState.GROUP,
        containerFactory = "teamAccessListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val stream = projectionStreams.teamAccess
        if (projectionRecordProcessor.processControlRecord(stream, record)) return
        val event = runCatching { TeamAccessState.parse(record.key(), objectMapper.readTree(record.value())) }
            .getOrElse {
                projectionRecordProcessor.quarantineRecord(stream, record, "Invalid team access state: ${it.message}")
                return
            }
        projectionRecordProcessor.applyRecord(stream, record) { teamAccessProjection.apply(event) }
    }
}

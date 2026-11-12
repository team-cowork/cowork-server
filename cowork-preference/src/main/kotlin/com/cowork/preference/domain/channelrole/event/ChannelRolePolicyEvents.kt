package com.cowork.preference.domain.channelrole.event

import com.cowork.preference.domain.channelrole.command.ChannelRolePolicyCommandContract
import com.cowork.preference.domain.channelrole.model.ChannelRolePolicyPermissions
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.json.JsonObject
import java.time.Instant

object ChannelRolePolicyEvents {
    fun channelRolePolicyState(
        teamId: Long,
        channelId: Long,
        roleId: Long,
        permissions: JsonObject?,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent {
        val canonicalPermissions = permissions?.let(ChannelRolePolicyPermissions::canonicalize)
        return PreferenceEvent(
            topic = PreferenceTopics.CHANNEL_ROLE_POLICY_STATE_TOPIC,
            key = ChannelRolePolicyCommandContract.policyKey(teamId, channelId, roleId),
            payload = basePayload(if (canonicalPermissions == null) "DELETE" else "UPSERT", occurredAt)
                .put("schemaVersion", ChannelRolePolicyCommandContract.SCHEMA_VERSION)
                .put("teamId", teamId)
                .put("channelId", channelId)
                .put("roleId", roleId)
                .put("permissions", canonicalPermissions?.copy())
                .put("snapshot", snapshot),
            occurredAt = occurredAt,
        )
    }

    fun channelRolePolicyCommandResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = PreferenceTopics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )
}

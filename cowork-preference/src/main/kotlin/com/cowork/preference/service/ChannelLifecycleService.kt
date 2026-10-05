package com.cowork.preference.service

import com.cowork.preference.messaging.ChannelLifecycleEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.vertx.sqlclient.SqlClient

class ChannelLifecycleService(
    private val projectionRepository: ChannelLifecycleProjectionRepository,
    private val policyRepository: ChannelRolePolicyRepository,
    private val outboxRepository: PreferenceOutboxRepository,
) {
    /**
     * 채널 삭제 fence 반영과 역할 정책 정리를 호출자의 transaction 안에서 함께 적용한다. projection row 갱신이
     * policy command와 같은 row lock을 잡으므로 먼저 commit된 정책도 이 정리 대상에 포함된다.
     */
    internal suspend fun apply(client: SqlClient, event: ChannelLifecycleEvent) {
        val applied = projectionRepository.apply(client, event)
        if (!applied || !event.deleted) return
        val teamId = event.teamId ?: return
        val tombstones = policyRepository.findPoliciesByChannel(client, teamId, event.channelId).map { policy ->
            policyRepository.deletePolicy(client, policy.teamId, policy.channelId, policy.roleId)
        }
        outboxRepository.enqueueAll(
            client,
            tombstones.map { tombstone ->
                PreferenceEvents.channelRolePolicyState(
                    teamId = tombstone.teamId,
                    channelId = tombstone.channelId,
                    roleId = tombstone.roleId,
                    permissions = null,
                    occurredAt = tombstone.stateOccurredAt,
                    snapshot = false,
                )
            },
        )
    }
}

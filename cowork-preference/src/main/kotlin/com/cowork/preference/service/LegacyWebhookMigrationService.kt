package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.LegacyWebhookManifest
import com.cowork.preference.domain.LegacyWebhookObservation
import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookMigrationConflict
import com.cowork.preference.domain.WebhookMigrationDecision
import com.cowork.preference.domain.WebhookMigrationMetadata
import com.cowork.preference.domain.WebhookMigrationPolicy
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.WebhookRepository
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

// TODO(#625): 구버전 자동 rollback 후 원본의 수정·삭제를 재이관하고 Preference 변경을 보존하는 임시 코드이다.
// v20261026.0 이후 추가 릴리즈까지 rollback이 없으면, 확인이 어려우면 2026-11-01(2026-10-24 + 8일) 이후 제거 가능하다.
// 자동 만료하지 않는다. 전체 이관·격리 해소를 확인하고 importer/readiness/publisher 의존을 함께 제거한다.
// 신규 ID sequence는 제거할 때도 낮추지 않는다.
class LegacyWebhookMigrationService(
    private val repository: WebhookRepository,
    private val channels: ChannelLifecycleProjectionRepository,
    private val outbox: PreferenceOutboxRepository,
) {
    suspend fun observe(client: SqlClient, observation: LegacyWebhookObservation) {
        val source = observation.webhook
        val channel = requireChannel(client, source.channelId)
        deleteRemovedChannel(client, channel)
        val current = repository.findForUpdate(client, source.id)
        val metadata = repository.migrationMetadata(client, source.id)
        val sourceHash = sourceHash(source)
        val decision = WebhookMigrationPolicy.observe(current, metadata, observation, sourceHash,
            repository.completedManifest(client, source.channelId))
        if (decision == WebhookMigrationDecision.IGNORE) return
        val candidate = if (channel.deleted) source.copy(deleted = true, isActive = false, secretHash = null,
            updatedAt = maxOf(source.updatedAt, channel.sourceOccurredAt)) else source
        val applied = when (decision) {
            WebhookMigrationDecision.IMPORT -> repository.importLegacy(client, candidate.copy(
                isActive = !channel.deleted && repository.legacyActive(client, source.channelId)))
            WebhookMigrationDecision.APPLY -> repository.applyLegacy(client, candidate,
                !channel.deleted && if (metadata!!.sourceDeleted) metadata.lastActive else current!!.isActive)
            else -> null
        }
        if (applied != null) outbox.enqueue(client, PreferenceEvents.webhookState(applied, snapshot = false))
        repository.saveMigrationMetadata(client, WebhookMigrationMetadata(
            source.id, source.channelId, source.createdBy, source.createdAt,
            applied?.updatedAt ?: metadata?.ownerAppliedUpdatedAt, source.updatedAt, observation.observedAt,
            observation.snapshotId, channel.deleted, sourceHash,
            applied?.takeUnless { it.deleted }?.isActive ?: metadata?.lastActive ?: current?.isActive ?: true,
        ))
    }

    suspend fun manifest(client: SqlClient, part: LegacyWebhookManifest) {
        val channel = requireChannel(client, part.channelId)
        // An old Preference binary may have checkpointed channel deletion without deleting owner webhooks.
        deleteRemovedChannel(client, channel)
        val completed = repository.completedManifest(client, part.channelId)
        if (completed != null) {
            if (part.observedAt < completed.observedAt) return
            if (part.observedAt == completed.observedAt && part.snapshotId != completed.snapshotId) {
                throw WebhookMigrationConflict("legacy manifest observation version conflicts")
            }
        }
        val previous = repository.manifestChunk(client, part)
        if (previous != null) {
            if (part.observedAt < previous.observedAt) return
            if (part.observedAt == previous.observedAt && part != previous) {
                throw WebhookMigrationConflict("legacy manifest chunk version conflicts")
            }
        }
        repository.saveManifestChunk(client, part, WebhookSecrets.hash(part.json().encode()))
        val full = LegacyWebhookManifest.complete(repository.manifestChunks(client, part), part) ?: return
        if (completed != null && full.observedAt == completed.observedAt && full != completed) {
            throw WebhookMigrationConflict("legacy completed manifest version conflicts")
        }
        repository.channelMigrationMetadata(client, full.channelId).forEach { metadata ->
            val current = repository.findForUpdate(client, metadata.webhookId)
            val decision = WebhookMigrationPolicy.delete(current, metadata, full)
            if (decision != WebhookMigrationDecision.IGNORE) {
                val deleted = if (decision == WebhookMigrationDecision.APPLY) repository.delete(client, metadata.webhookId) else null
                if (deleted != null) outbox.enqueue(client, PreferenceEvents.webhookState(deleted, snapshot = false))
                repository.saveMigrationMetadata(client, metadata.copy(
                    ownerAppliedUpdatedAt = deleted?.updatedAt ?: metadata.ownerAppliedUpdatedAt,
                    lastObservedAt = full.observedAt, lastSnapshotId = full.snapshotId, sourceDeleted = true,
                    lastActive = current?.takeUnless { it.deleted }?.isActive ?: metadata.lastActive,
                ))
            }
        }
        // Keep the last completed evidence separate: a partial later generation must not erase it.
        repository.saveCompletedManifest(client, full, WebhookSecrets.hash(full.json().encode()))
    }

    private suspend fun requireChannel(client: SqlClient, id: Long): ChannelLifecycleProjection =
        channels.findForUpdate(client, id) ?: throw ProjectionNotReadyException("legacy webhook channel projection is missing")

    private suspend fun deleteRemovedChannel(client: SqlClient, channel: ChannelLifecycleProjection) {
        if (channel.deleted) repository.deleteChannel(client, channel.channelId).forEach {
            outbox.enqueue(client, PreferenceEvents.webhookState(it, snapshot = false))
        }
    }

    private fun sourceHash(webhook: Webhook): String = WebhookSecrets.hash(JsonObject()
        .put("id", webhook.id).put("channelId", webhook.channelId).put("createdBy", webhook.createdBy)
        .put("createdAt", webhook.createdAt.toString()).put("name", webhook.name).put("avatarUrl", webhook.avatarUrl)
        .put("isSecure", webhook.isSecure).put("secretHash", webhook.secretHash).encode())
}

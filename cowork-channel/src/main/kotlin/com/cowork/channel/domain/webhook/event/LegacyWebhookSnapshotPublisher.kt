package com.cowork.channel.domain.webhook.event

import com.cowork.channel.domain.channel.repository.ChannelEventStateRepository
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import com.cowork.channel.global.outbox.OutboxWriter
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.channel.global.projection.ProjectionSnapshotLock
import com.cowork.channel.global.projection.ProjectionStreams
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// TODO(#625): 임시 rollback 호환 publisher. 구형 Channel의 변경·삭제를 재이관에서 놓치지 않도록
// 관측 시각과 채널별 전체 ID 목록을 발행한다. Preference의 새 ID 범위와 함께 구형 ID 충돌도 방지한다.
// v20261026.0 이후 추가 릴리즈가 배포되고 그동안 구버전 rollback이 없었으면 제거할 수 있다.
// 이를 확인하기 어려우면 2026-11-01(2026-10-24 + 8일) 이후 제거할 수 있으며 자동 만료하지 않는다.
// 전체 이관 완료·격리 해소와 구형 Channel 롤백 지원 종료를 확인한 뒤 이 publisher와 Preference의
// legacy importer·readiness 의존, 관련 토픽 설정을 제거한다. 더 이상 참조하지 않는 Channel DB의
// tb_webhooks 테이블과 원본 데이터도 이때 함께 제거한다.
@Component
class LegacyWebhookSnapshotPublisher(
    private val webhookRepository: WebhookRepository,
    private val channelStateRepository: ChannelEventStateRepository,
    private val outboxWriter: OutboxWriter,
    private val readinessState: ProjectionReadinessState,
    private val streams: ProjectionStreams,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val snapshotLock: ProjectionSnapshotLock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishStartupSnapshot() {
        snapshotLock.tryRun(SNAPSHOT_LOCK_NAME, ::publishSnapshot)
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun republishSnapshot() {
        snapshotLock.tryRun(SNAPSHOT_LOCK_NAME, ::publishSnapshot)
    }

    private fun publishSnapshot() {
        if (!readinessState.isCaughtUp(streams.channelStateUpstream)) return
        val snapshotId = UUID.randomUUID().toString()
        val observedAt = webhookRepository.currentSnapshotTime().toInstant(ZoneOffset.UTC)
        var afterId = 0L
        while (true) {
            val channels = checkNotNull(
                transaction.execute {
                    channelStateRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                },
            )
            if (channels.isEmpty()) break
            channels.forEach { channel ->
                publishChannelSnapshot(channel.channelId, snapshotId, observedAt)
            }
            afterId = channels.last().channelId
        }
        if (!readinessState.isCaughtUp(streams.channelStateUpstream)) return
        completionPublisher.publishCompleted(setOf(TOPIC))
    }

    private fun publishChannelSnapshot(channelId: Long, snapshotId: String, observedAt: Instant) {
        val webhookIds = mutableListOf<Long>()
        var afterId = 0L
        while (true) {
            val batchIds = checkNotNull(
                transaction.execute {
                    val webhooks = webhookRepository.findSnapshotBatch(channelId, afterId, PageRequest.of(0, PAGE_SIZE))
                    webhooks.forEach { webhook ->
                        outboxWriter.enqueue(
                            TOPIC,
                            webhook.id.toString(),
                            LegacyWebhookState.from(webhook, snapshotId, observedAt),
                        )
                    }
                    webhooks.map { it.id }
                },
            )
            if (batchIds.isEmpty()) break
            webhookIds.addAll(batchIds)
            afterId = batchIds.last()
        }
        LegacyWebhookManifest.chunks(channelId, webhookIds, snapshotId, observedAt).forEach { manifest ->
            transaction.executeWithoutResult {
                outboxWriter.enqueue(TOPIC, manifest.key(), manifest)
            }
        }
    }

    companion object {
        const val TOPIC = "channel.webhook-legacy.state.v1"
        private const val PAGE_SIZE = 500
        private const val SNAPSHOT_LOCK_NAME = "publishLegacyWebhookSnapshots"
    }
}

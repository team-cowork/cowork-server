package com.cowork.preference.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// TODO(#625): 임시 재이관 보정을 제거할 때 이 정책 테스트도 함께 제거한다.
class WebhookMigrationPolicyTest {
    private val createdAt = Instant.parse("2026-10-25T10:00:00Z")
    private val source = Webhook(
        id = 7, channelId = 11, name = "original", avatarUrl = null, isSecure = false,
        secretHash = null, isActive = true, deleted = false, createdBy = 3,
        createdAt = createdAt, updatedAt = createdAt.plusSeconds(10), modeChangedAt = null,
    )
    private val owner = source.copy(updatedAt = createdAt.plusSeconds(20))
    private val metadata = WebhookMigrationMetadata(
        webhookId = source.id, channelId = source.channelId, createdBy = source.createdBy,
        createdAt = source.createdAt, ownerAppliedUpdatedAt = owner.updatedAt,
        lastSourceOccurredAt = source.updatedAt, lastObservedAt = createdAt.plusSeconds(30),
        lastSnapshotId = "previous", sourceDeleted = false, sourceHash = "original-hash",
    )
    private val nextObservedAt = metadata.lastObservedAt.plusSeconds(10)
    private val changed = source.copy(name = "changed after rollback", updatedAt = source.updatedAt.plusSeconds(5))
    private val observation = LegacyWebhookObservation(changed, "next", nextObservedAt)
    private val absent = LegacyWebhookManifest(source.channelId, emptySet(), "next", nextObservedAt)

    @Test
    fun `Preference에서 수정하지 않았다면 rollback 중 구형 Channel에서 바꾼 내용을 적용한다`() {
        // Given: 현재 값은 직전 이관이 저장한 버전 그대로이다.
        // When
        val decision = WebhookMigrationPolicy.observe(owner, metadata, observation, "changed-hash", null)

        // Then
        assertEquals(WebhookMigrationDecision.APPLY, decision)
    }

    @Test
    fun `Preference에서 수정하거나 삭제한 웹훅은 구형 상태로 덮어쓰지 않는다`() {
        // Given
        val locallyChanged = owner.copy(name = "owner edit", updatedAt = owner.updatedAt.plusSeconds(1))
        val locallyDeleted = owner.copy(deleted = true, updatedAt = owner.updatedAt.plusSeconds(1))

        // When / Then
        for (current in listOf(locallyChanged, locallyDeleted, null)) {
            assertEquals(
                WebhookMigrationDecision.PROTECT,
                WebhookMigrationPolicy.observe(current, metadata, observation, "changed-hash", null),
            )
        }
    }

    @Test
    fun `늦게 도착한 관찰이나 이전 source 변경은 현재 이관 상태를 되돌리지 않는다`() {
        // Given
        val delayedObservation = observation.copy(observedAt = metadata.lastObservedAt.minusSeconds(1))
        val olderMutation = observation.copy(webhook = changed.copy(updatedAt = source.updatedAt.minusSeconds(1)))

        // When / Then
        for (stale in listOf(delayedObservation, olderMutation)) {
            assertEquals(
                WebhookMigrationDecision.IGNORE,
                WebhookMigrationPolicy.observe(owner, metadata, stale, "changed-hash", null),
            )
        }
    }

    @Test
    fun `같은 관찰 또는 source 변경 버전의 내용이 달라지면 충돌로 처리한다`() {
        // Given
        val sameObservation = LegacyWebhookObservation(source, metadata.lastSnapshotId, metadata.lastObservedAt)
        val sameMutation = LegacyWebhookObservation(source, "next", nextObservedAt)

        // When / Then
        for (conflicting in listOf(sameObservation, sameMutation)) {
            assertFailsWith<WebhookMigrationConflict> {
                WebhookMigrationPolicy.observe(owner, metadata, conflicting, "different-hash", null)
            }
        }
    }

    @Test
    fun `동일 ID의 채널 또는 생성 주체가 바뀌면 다른 웹훅으로 덮어쓰지 않는다`() {
        // Given
        val differentIdentities = listOf(
            changed.copy(channelId = source.channelId + 1),
            changed.copy(createdBy = source.createdBy + 1),
            changed.copy(createdAt = source.createdAt.plusSeconds(1)),
        )

        // When / Then
        for (different in differentIdentities) {
            assertFailsWith<WebhookMigrationConflict> {
                WebhookMigrationPolicy.observe(owner, metadata, observation.copy(webhook = different), "changed-hash", null)
            }
        }
    }

    @Test
    fun `완료 manifest에 없는 ID는 과거 상태 재생으로 부활하지 않지만 더 새 관찰은 이관할 수 있다`() {
        // Given: compaction으로 metadata 없이 과거 state만 남아도 부재 증명을 유지한다.
        val old = observation.copy(observedAt = absent.observedAt.minusSeconds(1))
        val newer = observation.copy(snapshotId = "after-manifest", observedAt = absent.observedAt.plusSeconds(1))

        // When / Then
        for (replayed in listOf(old, observation)) {
            assertEquals(
                WebhookMigrationDecision.IGNORE,
                WebhookMigrationPolicy.observe(null, null, replayed, "changed-hash", absent),
            )
        }
        assertEquals(
            WebhookMigrationDecision.IMPORT,
            WebhookMigrationPolicy.observe(null, null, newer, "changed-hash", absent),
        )
    }

    @Test
    fun `같은 관찰 세대의 완료 목록에서 사라진 미수정 웹훅은 삭제한다`() {
        // Given
        val sameGeneration = absent.copy(snapshotId = metadata.lastSnapshotId, observedAt = metadata.lastObservedAt)

        // When
        val decision = WebhookMigrationPolicy.delete(owner, metadata, sameGeneration)

        // Then
        assertEquals(WebhookMigrationDecision.APPLY, decision)
    }

    @Test
    fun `구형 목록에서 삭제돼도 Preference 수정은 보존하고 오래된 목록은 무시한다`() {
        // Given
        val locallyChanged = owner.copy(updatedAt = owner.updatedAt.plusSeconds(1))
        val oldManifest = absent.copy(observedAt = metadata.lastObservedAt.minusSeconds(1))

        // When / Then
        assertEquals(WebhookMigrationDecision.PROTECT, WebhookMigrationPolicy.delete(locallyChanged, metadata, absent))
        assertEquals(WebhookMigrationDecision.IGNORE, WebhookMigrationPolicy.delete(owner, metadata, oldManifest))
    }

    @Test
    fun `부분 목록에 다른 세대의 tail이 남아 있어도 삭제 판단용 완료 목록이 되지 않는다`() {
        // Given
        val first = absent.copy(webhookIds = setOf(7), chunkIndex = 0, chunkCount = 2)
        val oldTail = first.copy(webhookIds = setOf(8), snapshotId = "previous", chunkIndex = 1)
        val previousObservationTail = first.copy(webhookIds = setOf(8), observedAt = metadata.lastObservedAt, chunkIndex = 1)
        val previousLayoutTail = first.copy(webhookIds = setOf(8), chunkIndex = 1, chunkCount = 3)

        // When / Then
        for (parts in listOf(listOf(first), listOf(first, oldTail), listOf(first, previousObservationTail), listOf(first, previousLayoutTail))) {
            assertNull(LegacyWebhookManifest.complete(parts, first))
        }
    }

    @Test
    fun `모든 현재 세대 chunk를 합치고 이전 세대 tail의 ID는 삭제 판단 목록에서 제외한다`() {
        // Given
        val first = absent.copy(webhookIds = setOf(7), chunkIndex = 0, chunkCount = 2)
        val last = first.copy(webhookIds = setOf(8), chunkIndex = 1)
        val oldTail = first.copy(webhookIds = setOf(99), snapshotId = "previous", chunkIndex = 2, chunkCount = 3)

        // When
        val completed = LegacyWebhookManifest.complete(listOf(last, oldTail, first), first)

        // Then
        assertEquals(absent.copy(webhookIds = setOf(7, 8)), completed)
    }

    @Test
    fun `완료 목록의 서로 다른 chunk에 같은 웹훅이 있으면 충돌로 처리한다`() {
        // Given
        val first = absent.copy(webhookIds = setOf(7), chunkIndex = 0, chunkCount = 2)
        val last = first.copy(webhookIds = setOf(7, 8), chunkIndex = 1)

        // When / Then
        assertFailsWith<WebhookMigrationConflict> {
            LegacyWebhookManifest.complete(listOf(first, last), first)
        }
    }

    @Test
    fun `단일 빈 manifest는 채널에 구형 웹훅이 없다는 완료 목록이다`() {
        // Given / When
        val completed = LegacyWebhookManifest.complete(listOf(absent), absent)

        // Then
        assertEquals(emptySet(), completed?.webhookIds)
        assertEquals(absent, completed)
    }

    @Test
    fun `구형 ID 영역과 Preference 예약 영역은 겹치지 않고 JavaScript 정밀도 범위를 넘지 않는다`() {
        // Given
        val firstOwner = 4_503_599_627_370_496L
        val maximum = 9_007_199_254_740_991L

        // When / Then
        assertEquals(firstOwner, WebhookMigrationIds.FIRST_OWNER_ID)
        assertEquals(maximum, WebhookMigrationIds.MAX_SAFE_ID)
        assertFalse(WebhookMigrationIds.isLegacy(0))
        assertFalse(WebhookMigrationIds.isOwner(0))
        assertTrue(WebhookMigrationIds.isLegacy(1))
        assertTrue(WebhookMigrationIds.isLegacy(firstOwner - 1))
        assertFalse(WebhookMigrationIds.isOwner(firstOwner - 1))
        assertFalse(WebhookMigrationIds.isLegacy(firstOwner))
        assertTrue(WebhookMigrationIds.isOwner(firstOwner))
        assertTrue(WebhookMigrationIds.isOwner(maximum))
        assertFalse(WebhookMigrationIds.isLegacy(maximum))
        assertFalse(WebhookMigrationIds.isOwner(maximum + 1))
    }
}

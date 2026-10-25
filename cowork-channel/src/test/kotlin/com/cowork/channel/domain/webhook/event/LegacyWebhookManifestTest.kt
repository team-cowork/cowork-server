package com.cowork.channel.domain.webhook.event

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class LegacyWebhookManifestTest :
    DescribeSpec({
        val snapshotId = "13b3bfca-26d5-4a77-9ff9-138e685ba5b4"
        val observedAt = Instant.parse("2026-10-25T03:00:00.123456Z")

        describe("LegacyWebhookManifest는") {
            context("마지막 웹훅도 삭제된 채널이면") {
                it("전체 삭제를 판정할 수 있는 빈 snapshot을 발행한다") {
                    val chunks = LegacyWebhookManifest.chunks(3, emptyList(), snapshotId, observedAt)

                    chunks.size shouldBe 1
                    chunks.single().webhookIds shouldBe emptyList()
                    chunks.single().chunkIndex shouldBe 0
                    chunks.single().chunkCount shouldBe 1
                    chunks.single().key() shouldBe "channel:3:0"
                }
            }

            context("웹훅 수가 한 레코드 한도를 넘으면") {
                it("모든 ID를 중복 없이 분할하고 완성 여부와 관측 정보를 보존한다") {
                    val ids = (1L..2501L).toList()

                    val chunks = LegacyWebhookManifest.chunks(3, ids, snapshotId, observedAt)

                    chunks.map { it.webhookIds.size } shouldBe listOf(1000, 1000, 501)
                    chunks.flatMap { it.webhookIds } shouldBe ids
                    chunks.map { it.chunkIndex } shouldBe listOf(0, 1, 2)
                    chunks.map { it.key() }.toSet().size shouldBe 3
                    chunks.forEach {
                        it.chunkCount shouldBe 3
                        it.snapshotId shouldBe snapshotId
                        it.observedAt shouldBe observedAt
                    }
                }
            }

            context("후속 snapshot에서 목록이 줄어들면") {
                it("compacted 토픽의 같은 조각 키로 새 전체 목록을 교체한다") {
                    val previous = LegacyWebhookManifest.chunks(3, (1L..1001L).toList(), snapshotId, observedAt)
                    val current = LegacyWebhookManifest.chunks(
                        3,
                        listOf(7L),
                        "fe9b333c-fba3-493b-9301-802454a378dc",
                        observedAt.plusSeconds(300),
                    )

                    current.single().key() shouldBe previous.first().key()
                    current.single().chunkCount shouldBe 1
                    current.single().webhookIds shouldBe listOf(7L)
                }
            }
        }
    })

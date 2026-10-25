package com.cowork.channel.domain.webhook.event

import com.cowork.channel.domain.webhook.entity.Webhook
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDateTime

class LegacyWebhookStateTest :
    DescribeSpec({
        val snapshotId = "13b3bfca-26d5-4a77-9ff9-138e685ba5b4"
        val observedAt = Instant.parse("2026-10-25T03:00:00.123456Z")

        fun webhook(isSecure: Boolean, token: String?) = Webhook(
            id = 7,
            channelId = 3,
            name = "deploy",
            isSecure = isSecure,
            token = token,
            avatarUrl = null,
            createdBy = 9,
            createdAt = LocalDateTime.parse("2026-10-25T01:02:03.123456"),
            updatedAt = LocalDateTime.parse("2026-10-25T02:03:04.567890"),
        )

        describe("LegacyWebhookState 변환은") {
            context("보안 웹훅을 이관하면") {
                it("기존 인증값의 해시와 원본 변경 시각을 보존한다") {
                    val event = LegacyWebhookState.from(webhook(true, "secret"), snapshotId, observedAt)

                    event.secretHash shouldBe "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b"
                    event.createdAt shouldBe Instant.parse("2026-10-25T01:02:03.123456Z")
                    event.occurredAt shouldBe Instant.parse("2026-10-25T02:03:04.567890Z")
                    event.snapshotId shouldBe snapshotId
                    event.observedAt shouldBe observedAt
                    LegacyWebhookState.from(webhook(true, "secret"), snapshotId, observedAt) shouldBe event
                }
            }

            context("공개 웹훅에 이전 인증값이 남아 있으면") {
                it("공개 모드를 유지하고 인증값을 전달하지 않는다") {
                    val event = LegacyWebhookState.from(webhook(false, "old-secret"), snapshotId, observedAt)

                    event.isSecure shouldBe false
                    event.secretHash shouldBe null
                }
            }

            context("보안 웹훅의 인증값이 유실되었으면") {
                it("소유 서비스가 잘못된 상태를 격리하도록 공개 모드로 바꾸지 않는다") {
                    val event = LegacyWebhookState.from(webhook(true, null), snapshotId, observedAt)

                    event.isSecure shouldBe true
                    event.secretHash shouldBe null
                }
            }

            context("같은 원본을 다음 snapshot에서 다시 관측하면") {
                it("원본 변경 시각과 새 관측 시각을 구분한다") {
                    val source = webhook(true, "secret")
                    val previous = LegacyWebhookState.from(source, snapshotId, observedAt)
                    val current = LegacyWebhookState.from(
                        source,
                        "fe9b333c-fba3-493b-9301-802454a378dc",
                        observedAt.plusSeconds(300),
                    )

                    current.occurredAt shouldBe previous.occurredAt
                    current.secretHash shouldBe previous.secretHash
                    current.observedAt shouldBe observedAt.plusSeconds(300)
                }
            }
        }
    })

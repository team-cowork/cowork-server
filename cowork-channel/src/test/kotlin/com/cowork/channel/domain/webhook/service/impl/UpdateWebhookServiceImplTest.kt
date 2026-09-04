package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.webhook.entity.Webhook
import com.cowork.channel.domain.webhook.presentation.data.request.UpdateWebhookRequest
import com.cowork.channel.domain.webhook.service.WebhookAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpdateWebhookServiceImplTest :
    DescribeSpec({

        lateinit var accessGuard: WebhookAccessGuard
        lateinit var service: UpdateWebhookServiceImpl

        fun webhook(channelId: Long = 1L) = Webhook(
            id = 7L,
            channelId = channelId,
            name = "deploy",
            avatarUrl = null,
            createdBy = 20L,
        )

        beforeEach {
            accessGuard = mockk(relaxed = true)
            service = UpdateWebhookServiceImpl(accessGuard)
        }

        describe("UpdateWebhookServiceImpl 클래스의 updateWebhook 메서드는") {
            context("웹훅이 다른 채널에 속하면") {
                it("BAD_REQUEST로 거부한다") {
                    every { accessGuard.findWebhookOrThrow(7L) } returns webhook(channelId = 2L)

                    val error = shouldThrow<ExpectedException> {
                        service.updateWebhook(20L, 1L, 7L, UpdateWebhookRequest(name = "changed"))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("관리 권한이 있고 같은 채널 웹훅이면") {
                it("요청한 속성을 변경하고 보안 토큰을 발급한다") {
                    every { accessGuard.findWebhookOrThrow(7L) } returns webhook()

                    val result = service.updateWebhook(
                        20L,
                        1L,
                        7L,
                        UpdateWebhookRequest(
                            name = "changed",
                            avatarUrl = "https://example.com/a.png",
                            isSecure = true,
                        ),
                    )

                    result.name shouldBe "changed"
                    result.avatarUrl shouldBe "https://example.com/a.png"
                    result.isSecure shouldBe true
                    result.token.shouldNotBeNull()
                }
            }
        }
    })

package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.webhook.entity.Webhook
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import com.cowork.channel.domain.webhook.service.WebhookAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DeleteWebhookServiceImplTest :
    DescribeSpec({

        lateinit var repository: WebhookRepository
        lateinit var accessGuard: WebhookAccessGuard
        lateinit var service: DeleteWebhookServiceImpl

        fun webhook(channelId: Long = 1L) = Webhook(
            id = 7L,
            channelId = channelId,
            name = "deploy",
            avatarUrl = null,
            createdBy = 20L,
        )

        beforeEach {
            repository = mockk(relaxed = true)
            accessGuard = mockk(relaxed = true)
            service = DeleteWebhookServiceImpl(repository, accessGuard)
        }

        describe("DeleteWebhookServiceImpl 클래스의 deleteWebhook 메서드는") {
            context("웹훅이 다른 채널에 속하면") {
                it("BAD_REQUEST로 거부하고 삭제하지 않는다") {
                    every { accessGuard.findWebhookOrThrow(7L) } returns webhook(channelId = 2L)

                    val error = shouldThrow<ExpectedException> { service.deleteWebhook(20L, 1L, 7L) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { repository.delete(any()) }
                }
            }

            context("관리 권한이 있고 같은 채널 웹훅이면") {
                it("웹훅을 삭제한다") {
                    val target = webhook()
                    every { accessGuard.findWebhookOrThrow(7L) } returns target

                    service.deleteWebhook(20L, 1L, 7L)

                    verify(exactly = 1) { repository.delete(target) }
                }
            }
        }
    })

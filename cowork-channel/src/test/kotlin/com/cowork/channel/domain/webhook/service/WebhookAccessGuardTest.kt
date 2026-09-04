package com.cowork.channel.domain.webhook.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class WebhookAccessGuardTest :
    DescribeSpec({

        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var teamPermissionService: TeamPermissionService
        lateinit var guard: WebhookAccessGuard

        fun channel(type: ChannelType = ChannelType.TEXT, createdBy: Long = 10L) = Channel(
            id = 1L,
            teamId = 100L,
            name = "general",
            type = type,
            viewType = ChannelViewType.TEXT,
            description = null,
            isPrivate = false,
            createdBy = createdBy,
        )

        beforeEach {
            channelAccessGuard = mockk()
            teamPermissionService = mockk()
            guard = WebhookAccessGuard(mockk<WebhookRepository>(), channelAccessGuard, teamPermissionService)
        }

        describe("WebhookAccessGuard 클래스의 requireWebhookManager 메서드는") {
            context("TEXT 채널이 아니면") {
                it("BAD_REQUEST로 거부한다") {
                    every { channelAccessGuard.findChannelOrThrow(1L) } returns channel(ChannelType.VOICE)

                    val error = shouldThrow<ExpectedException> { guard.requireWebhookManager(1L, 20L) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("채널 생성자도 팀 관리자도 아니면") {
                it("FORBIDDEN으로 거부한다") {
                    val text = channel()
                    every { channelAccessGuard.findChannelOrThrow(1L) } returns text
                    every { channelAccessGuard.requireTeamChannel(text) } returns 100L
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 20L) } returns false

                    val error = shouldThrow<ExpectedException> { guard.requireWebhookManager(1L, 20L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("팀 관리자이면") {
                it("TEXT 채널의 웹훅 관리를 허용한다") {
                    val text = channel()
                    every { channelAccessGuard.findChannelOrThrow(1L) } returns text
                    every { channelAccessGuard.requireTeamChannel(text) } returns 100L
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 20L) } returns true

                    guard.requireWebhookManager(1L, 20L) shouldBe text
                }
            }
        }
    })

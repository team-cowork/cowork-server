package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.repository.ThreadRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateThreadServiceImplTest :
    DescribeSpec({

        lateinit var threadRepository: ThreadRepository
        lateinit var permissions: ChannelPermissionSupport
        lateinit var evaluator: ChannelMessageReadPolicyEvaluator
        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var service: CreateThreadServiceImpl
        val channel = Channel(
            id = 1L,
            teamId = 100L,
            name = "general",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            isPrivate = false,
            createdBy = 10L,
        )
        val request = CreateThreadRequest("질문", "message-1")

        beforeEach {
            threadRepository = mockk(relaxed = true)
            permissions = mockk(relaxed = true)
            evaluator = mockk(relaxed = true)
            channelAccessGuard = mockk()
            every { channelAccessGuard.findChannelOrThrow(1L) } returns channel
            every { channelAccessGuard.requireTeamChannel(channel) } returns 100L
            service = CreateThreadServiceImpl(threadRepository, permissions, evaluator, channelAccessGuard)
        }

        describe("CreateThreadServiceImpl 클래스의 createThread 메서드는") {
            context("요청자가 스레드 작성 권한이 없으면") {
                it("FORBIDDEN으로 거부한다") {
                    every { evaluator.requirePermission(100L, 20L, channel, "thread_reply") } throws
                        ExpectedException("스레드 작성 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    val error = shouldThrow<ExpectedException> { service.createThread(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { threadRepository.save(any()) }
                }
            }

            context("요청자가 스레드 작성 권한이 있으면") {
                it("부모 메시지에 연결된 스레드를 생성한다") {
                    val saved = slot<Thread>()
                    every { evaluator.requirePermission(100L, 20L, channel, "thread_reply") } returns Unit
                    every { threadRepository.save(capture(saved)) } answers { saved.captured }

                    val result = service.createThread(20L, 1L, request)

                    result.name shouldBe "질문"
                    saved.captured.channelId shouldBe 1L
                    saved.captured.parentMessageId shouldBe "message-1"
                    saved.captured.createdBy shouldBe 20L
                }
            }
        }
    })

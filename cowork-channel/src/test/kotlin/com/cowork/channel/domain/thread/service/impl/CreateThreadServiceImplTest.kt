package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.entity.ThreadStatus
import com.cowork.channel.domain.thread.event.ThreadParentCommand
import com.cowork.channel.domain.thread.event.ThreadParentContract
import com.cowork.channel.domain.thread.event.ThreadStatePublisher
import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.global.outbox.OutboxWriter
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
        lateinit var publisher: ThreadStatePublisher
        lateinit var outboxWriter: OutboxWriter
        lateinit var service: CreateThreadServiceImpl
        val parentMessageId = "507f1f77bcf86cd799439011"
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
        val request = CreateThreadRequest("질문", parentMessageId)

        beforeEach {
            threadRepository = mockk(relaxed = true)
            permissions = mockk(relaxed = true)
            evaluator = mockk(relaxed = true)
            channelAccessGuard = mockk()
            publisher = mockk(relaxed = true)
            outboxWriter = mockk(relaxed = true)
            every { channelAccessGuard.findChannelForUpdateOrThrow(1L) } returns channel
            every { channelAccessGuard.requireTeamChannel(channel) } returns 100L
            every { channelAccessGuard.requireNotArchived(any()) } returns Unit
            every { threadRepository.existsByParentMessageId(parentMessageId) } returns false
            service = CreateThreadServiceImpl(
                threadRepository,
                permissions,
                evaluator,
                channelAccessGuard,
                publisher,
                outboxWriter,
            )
        }

        describe("CreateThreadServiceImpl 클래스의 createThread 메서드는") {
            context("요청자가 스레드 작성 권한이 없으면") {
                it("FORBIDDEN으로 거부한다") {
                    every { evaluator.requirePermission(100L, 20L, channel, "thread_reply") } throws
                        ExpectedException("스레드 작성 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    val error = shouldThrow<ExpectedException> { service.createThread(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { threadRepository.saveAndFlush(any()) }
                }
            }

            context("시작 메시지 ID가 ObjectId 형식이 아니면") {
                it("BAD_REQUEST로 거부한다") {
                    val error = shouldThrow<ExpectedException> {
                        service.createThread(20L, 1L, CreateThreadRequest("질문", "message-1"))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { threadRepository.saveAndFlush(any()) }
                }
            }

            context("시작 메시지에 이미 스레드가 있으면") {
                it("CONFLICT로 거부한다") {
                    every { threadRepository.existsByParentMessageId(parentMessageId) } returns true

                    val error = shouldThrow<ExpectedException> { service.createThread(20L, 1L, request) }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify(exactly = 0) { threadRepository.saveAndFlush(any()) }
                    verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any(), any(), any()) }
                }
            }

            context("요청자가 스레드 작성 권한이 있으면") {
                it("검증 대기 스레드를 만들고 상태와 시작 메시지 검증 command를 발행한다") {
                    val saved = slot<Thread>()
                    every { threadRepository.saveAndFlush(capture(saved)) } answers {
                        Thread(
                            id = 7L,
                            channelId = saved.captured.channelId,
                            name = saved.captured.name,
                            parentMessageId = saved.captured.parentMessageId,
                            createdBy = saved.captured.createdBy,
                        )
                    }

                    val result = service.createThread(20L, 1L, request)

                    result.status shouldBe ThreadStatus.PENDING
                    saved.captured.channelId shouldBe 1L
                    saved.captured.createdBy shouldBe 20L
                    verify { publisher.publish(match { it.id == 7L }) }
                    verify {
                        outboxWriter.enqueue(
                            ThreadParentContract.COMMAND_TOPIC,
                            "7",
                            ThreadParentCommand(threadId = 7L, channelId = 1L, parentMessageId = parentMessageId),
                        )
                    }
                }
            }
        }
    })

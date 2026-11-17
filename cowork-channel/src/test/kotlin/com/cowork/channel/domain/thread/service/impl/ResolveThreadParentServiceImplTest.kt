package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.entity.ThreadStatus
import com.cowork.channel.domain.thread.event.ThreadParentContract
import com.cowork.channel.domain.thread.event.ThreadParentResult
import com.cowork.channel.domain.thread.event.ThreadStatePublisher
import com.cowork.channel.domain.thread.repository.ThreadRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ResolveThreadParentServiceImplTest :
    DescribeSpec({

        lateinit var threadRepository: ThreadRepository
        lateinit var publisher: ThreadStatePublisher
        lateinit var service: ResolveThreadParentServiceImpl
        val parentMessageId = "507f1f77bcf86cd799439011"

        fun thread(status: ThreadStatus = ThreadStatus.PENDING) = Thread(
            id = 7L,
            channelId = 1L,
            name = "질문",
            parentMessageId = parentMessageId,
            createdBy = 20L,
            status = status,
        )

        fun result(accepted: Boolean, channelId: Long = 1L) = ThreadParentResult(
            schemaVersion = ThreadParentContract.SCHEMA_VERSION,
            threadId = 7L,
            channelId = channelId,
            parentMessageId = parentMessageId,
            accepted = accepted,
            reason = if (accepted) null else "PARENT_NOT_FOUND",
        )

        beforeEach {
            threadRepository = mockk(relaxed = true)
            publisher = mockk(relaxed = true)
            service = ResolveThreadParentServiceImpl(threadRepository, publisher)
        }

        describe("ResolveThreadParentServiceImpl 클래스의 resolve 메서드는") {
            context("시작 메시지가 확인되면") {
                it("스레드를 확정하고 상태를 발행한다") {
                    val pending = thread()
                    every { threadRepository.findByIdForUpdate(7L) } returns pending

                    service.resolve(result(accepted = true))

                    pending.status shouldBe ThreadStatus.ACTIVE
                    verify { publisher.publish(pending) }
                    verify(exactly = 0) { threadRepository.delete(any()) }
                }
            }

            context("시작 메시지가 거부되면") {
                it("스레드를 지우고 거부 상태를 삭제로 발행한다") {
                    val pending = thread()
                    every { threadRepository.findByIdForUpdate(7L) } returns pending

                    service.resolve(result(accepted = false))

                    pending.status shouldBe ThreadStatus.REJECTED
                    verify { publisher.publish(pending, deleted = true) }
                    verify { threadRepository.delete(pending) }
                }
            }

            context("이미 처리한 스레드이면") {
                it("다시 바꾸지 않는다") {
                    every { threadRepository.findByIdForUpdate(7L) } returns thread(ThreadStatus.ACTIVE)

                    service.resolve(result(accepted = false))

                    verify(exactly = 0) { publisher.publish(any(), any()) }
                    verify(exactly = 0) { threadRepository.delete(any()) }
                }
            }

            context("스레드가 이미 지워졌으면") {
                it("결과를 무시한다") {
                    every { threadRepository.findByIdForUpdate(7L) } returns null

                    service.resolve(result(accepted = true))

                    verify(exactly = 0) { publisher.publish(any(), any()) }
                }
            }

            context("결과의 채널이 스레드와 다르면") {
                it("잘못된 결과로 거부한다") {
                    every { threadRepository.findByIdForUpdate(7L) } returns thread()

                    shouldThrow<IllegalArgumentException> { service.resolve(result(accepted = true, channelId = 2L)) }
                    verify(exactly = 0) { publisher.publish(any(), any()) }
                }
            }
        }
    })

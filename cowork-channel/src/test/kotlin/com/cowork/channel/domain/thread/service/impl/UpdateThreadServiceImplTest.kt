package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.presentation.data.request.UpdateThreadRequest
import com.cowork.channel.domain.thread.repository.ThreadRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class UpdateThreadServiceImplTest :
    DescribeSpec({

        lateinit var threadRepository: ThreadRepository
        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var teamPermissionService: TeamPermissionService
        lateinit var service: UpdateThreadServiceImpl
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

        fun thread(channelId: Long = 1L, createdBy: Long = 20L) = Thread(
            id = 7L,
            channelId = channelId,
            name = "기존 이름",
            parentMessageId = "message-1",
            createdBy = createdBy,
        )

        beforeEach {
            threadRepository = mockk()
            channelAccessGuard = mockk()
            teamPermissionService = mockk()
            every { channelAccessGuard.findChannelOrThrow(1L) } returns channel
            every { channelAccessGuard.requireTeamChannel(channel) } returns 100L
            service = UpdateThreadServiceImpl(threadRepository, channelAccessGuard, teamPermissionService)
        }

        describe("UpdateThreadServiceImpl 클래스의 updateThread 메서드는") {
            context("스레드가 다른 채널에 속하면") {
                it("BAD_REQUEST로 거부한다") {
                    every { threadRepository.findById(7L) } returns Optional.of(thread(channelId = 2L))

                    val error = shouldThrow<ExpectedException> {
                        service.updateThread(20L, 1L, 7L, UpdateThreadRequest(name = "변경"))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("요청자가 스레드 생성자도 채널 생성자도 팀 관리자도 아니면") {
                it("FORBIDDEN으로 거부한다") {
                    every { threadRepository.findById(7L) } returns Optional.of(thread())
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 30L) } returns false

                    val error = shouldThrow<ExpectedException> {
                        service.updateThread(30L, 1L, 7L, UpdateThreadRequest(name = "변경"))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("요청자가 스레드 생성자이면") {
                it("이름과 보관 상태를 변경한다") {
                    val existing = thread()
                    every { threadRepository.findById(7L) } returns Optional.of(existing)
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 20L) } returns false

                    val result = service.updateThread(
                        20L,
                        1L,
                        7L,
                        UpdateThreadRequest(name = "변경", isArchived = true),
                    )

                    result.name shouldBe "변경"
                    result.isArchived shouldBe true
                }
            }
        }
    })

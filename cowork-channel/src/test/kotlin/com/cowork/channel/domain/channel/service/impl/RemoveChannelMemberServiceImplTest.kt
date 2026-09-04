package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class RemoveChannelMemberServiceImplTest :
    DescribeSpec({

        lateinit var memberRepository: ChannelMemberRepository
        lateinit var teamPermissionService: TeamPermissionService
        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var service: RemoveChannelMemberServiceImpl

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

        beforeEach {
            memberRepository = mockk(relaxed = true)
            teamPermissionService = mockk()
            channelAccessGuard = mockk()
            every { channelAccessGuard.findChannelOrThrow(1L) } returns channel
            every { channelAccessGuard.requireTeamChannel(channel) } returns 100L
            service = RemoveChannelMemberServiceImpl(
                memberRepository,
                teamPermissionService,
                mockk<ChannelMemberEventPublisher>(relaxed = true),
                channelAccessGuard,
            )
        }

        describe("RemoveChannelMemberServiceImpl 클래스의 execute 메서드는") {
            context("대상 멤버가 다른 채널 소속이면") {
                it("BAD_REQUEST로 거부하고 삭제하지 않는다") {
                    every { memberRepository.findById(7L) } returns Optional.of(ChannelMember(7L, 2L, 20L))

                    val error = shouldThrow<ExpectedException> { service.execute(10L, 1L, 7L) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }

            context("대상 멤버가 채널 생성자이면") {
                it("채널 이탈을 거부한다") {
                    every { memberRepository.findById(7L) } returns Optional.of(ChannelMember(7L, 1L, 10L))

                    val error = shouldThrow<ExpectedException> { service.execute(10L, 1L, 7L) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }

            context("요청자가 생성자도 본인도 팀 관리자도 아니면") {
                it("FORBIDDEN으로 거부한다") {
                    every { memberRepository.findById(7L) } returns Optional.of(ChannelMember(7L, 1L, 20L))
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 30L) } returns false

                    val error = shouldThrow<ExpectedException> { service.execute(30L, 1L, 7L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.delete(any()) }
                }
            }

            context("일반 멤버가 자기 자신을 제거하면") {
                it("채널에서 탈퇴한다") {
                    val member = ChannelMember(7L, 1L, 20L)
                    every { memberRepository.findById(7L) } returns Optional.of(member)
                    every { teamPermissionService.isTeamOwnerOrAdmin(100L, 20L) } returns false

                    service.execute(20L, 1L, 7L)

                    verify(exactly = 1) { memberRepository.delete(member) }
                }
            }
        }
    })

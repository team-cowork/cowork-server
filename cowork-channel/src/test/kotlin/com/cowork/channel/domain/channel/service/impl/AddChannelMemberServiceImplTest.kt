package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class AddChannelMemberServiceImplTest :
    DescribeSpec({

        lateinit var channelRepository: ChannelRepository
        lateinit var channelMemberRepository: ChannelMemberRepository
        lateinit var teamPermission: TeamPermissionService
        lateinit var channelMemberEventPublisher: ChannelMemberEventPublisher
        lateinit var channelAccessGuard: ChannelAccessGuard
        lateinit var channelPermissionSupport: ChannelPermissionSupport
        lateinit var service: AddChannelMemberServiceImpl

        beforeEach {
            channelRepository = mockk(relaxed = true)
            channelMemberRepository = mockk(relaxed = true)
            teamPermission = mockk()
            channelMemberEventPublisher = mockk(relaxed = true)
            channelAccessGuard = ChannelAccessGuard(channelRepository)
            channelPermissionSupport = ChannelPermissionSupport(
                channelMemberRepository,
                teamPermission,
                mockk(relaxed = true),
            )
            service = AddChannelMemberServiceImpl(
                channelMemberRepository,
                teamPermission,
                channelMemberEventPublisher,
                channelAccessGuard,
                channelPermissionSupport,
            )
        }

        fun channel(id: Long = 1L, teamId: Long = 100L, createdBy: Long = 1L, isPrivate: Boolean = false) = Channel(
            id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
            description = null, isPrivate = isPrivate, position = 0, createdBy = createdBy,
        )

        fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
            id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
            description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
        )

        describe("AddChannelMemberServiceImpl 클래스의") {
            describe("addMember 메서드는") {
                context("비공개 채널이고 요청자가 채널 생성자가 아닌 경우") {
                    it("FORBIDDEN을 던지고 멤버를 저장하지 않는다") {
                        val ch = channel(createdBy = 99L, isPrivate = true)
                        every { channelRepository.findById(1L) } returns Optional.of(ch)
                        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

                        val ex = shouldThrow<ExpectedException> {
                            service.execute(1L, 1L, AddMemberRequest(userId = 50L))
                        }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                        verify(exactly = 0) { channelMemberRepository.save(any()) }
                    }
                }

                context("추가 대상이 팀 멤버가 아닌 경우") {
                    it("BAD_REQUEST를 던진다") {
                        val ch = channel(createdBy = 1L)
                        every { channelRepository.findById(1L) } returns Optional.of(ch)
                        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
                        every { teamPermission.isTeamMember(100L, 50L) } returns false

                        val ex = shouldThrow<ExpectedException> {
                            service.execute(1L, 1L, AddMemberRequest(userId = 50L))
                        }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("DM 채널인 경우") {
                    it("BAD_REQUEST를 던지고 멤버를 저장하지 않는다") {
                        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

                        val ex = shouldThrow<ExpectedException> {
                            service.execute(1L, 1L, AddMemberRequest(userId = 50L))
                        }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                        verify(exactly = 0) { channelMemberRepository.save(any()) }
                    }
                }

                context("정상적으로 추가 가능한 경우") {
                    it("요청된 userId로 채널 멤버를 저장한다") {
                        val ch = channel(createdBy = 1L)
                        every { channelRepository.findById(1L) } returns Optional.of(ch)
                        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
                        every { teamPermission.isTeamMember(100L, 50L) } returns true
                        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 50L) } returns false
                        val savedMember = slot<ChannelMember>()
                        every { channelMemberRepository.save(capture(savedMember)) } answers { savedMember.captured }

                        service.execute(1L, 1L, AddMemberRequest(userId = 50L))

                        savedMember.captured.userId shouldBe 50L
                    }
                }
            }
        }
    })

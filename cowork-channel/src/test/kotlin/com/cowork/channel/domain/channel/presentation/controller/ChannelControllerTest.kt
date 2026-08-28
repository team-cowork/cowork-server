package com.cowork.channel.domain.channel.presentation.controller

import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.AddChannelMemberService
import com.cowork.channel.domain.channel.service.CreateChannelService
import com.cowork.channel.domain.channel.service.DeleteChannelService
import com.cowork.channel.domain.channel.service.QueryChannelMembersService
import com.cowork.channel.domain.channel.service.QueryChannelService
import com.cowork.channel.domain.channel.service.RemoveChannelMemberService
import com.cowork.channel.domain.channel.service.UpdateChannelService
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

class ChannelControllerTest :
    DescribeSpec({

        lateinit var createChannelService: CreateChannelService
        lateinit var queryChannelService: QueryChannelService
        lateinit var updateChannelService: UpdateChannelService
        lateinit var deleteChannelService: DeleteChannelService
        lateinit var addChannelMemberService: AddChannelMemberService
        lateinit var queryChannelMembersService: QueryChannelMembersService
        lateinit var removeChannelMemberService: RemoveChannelMemberService
        lateinit var controller: ChannelController

        beforeEach {
            createChannelService = mockk()
            queryChannelService = mockk()
            updateChannelService = mockk()
            deleteChannelService = mockk(relaxed = true)
            addChannelMemberService = mockk()
            queryChannelMembersService = mockk()
            removeChannelMemberService = mockk(relaxed = true)
            controller = ChannelController(
                createChannelService,
                queryChannelService,
                updateChannelService,
                deleteChannelService,
                addChannelMemberService,
                queryChannelMembersService,
                removeChannelMemberService,
                ObjectMapper(),
            )
        }

        fun channelResponse(id: Long = 1L, createdBy: Long = 1L) = ChannelResponse(
            id = id,
            teamId = 100L,
            projectId = null,
            name = "채널",
            type = "TEXT",
            viewType = "TEXT",
            description = null,
            isPrivate = false,
            position = 0,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )

        describe("ChannelController 클래스의") {
            describe("createChannel 메서드는") {
                context("X-User-Id 헤더로 전달된 사용자 ID를 받은 경우") {
                    it("헤더의 userId를 그대로 서비스에 전달한다") {
                        val userIdSlot = slot<Long>()
                        every { createChannelService.execute(capture(userIdSlot), any()) } returns channelResponse()

                        val request = CreateChannelRequest(
                            teamId = 100L,
                            name = "새 채널",
                            type = "TEXT",
                            viewType = "TEXT",
                        )
                        controller.createChannel(userId = 42L, request = request)

                        userIdSlot.captured shouldBe 42L
                        verify(exactly = 1) { createChannelService.execute(42L, request) }
                    }
                }
            }

            describe("getChannel 메서드는") {
                context("다른 X-User-Id 값이 전달될 때마다") {
                    it("매번 해당 userId로 서비스를 호출한다 — 헤더 값을 신뢰하여 그대로 전달") {
                        every { queryChannelService.execute(any(), 1L) } returns channelResponse(id = 1L)

                        controller.getChannel(userId = 7L, channelId = 1L)
                        controller.getChannel(userId = 999L, channelId = 1L)

                        verify(exactly = 1) { queryChannelService.execute(7L, 1L) }
                        verify(exactly = 1) { queryChannelService.execute(999L, 1L) }
                    }
                }
            }

            describe("deleteChannel 메서드는") {
                context("X-User-Id 헤더가 주어진 경우") {
                    it("바디 없이 헤더의 userId만으로 삭제 서비스를 호출한다") {
                        controller.deleteChannel(userId = 5L, channelId = 10L)

                        verify(exactly = 1) { deleteChannelService.execute(5L, 10L) }
                    }
                }
            }

            describe("addMember 메서드는") {
                context("요청자의 X-User-Id와 대상 userId가 다른 경우") {
                    it("요청자 식별은 헤더에서, 대상 식별은 바디에서 각각 읽어 서비스에 전달한다") {
                        val response = ChannelMemberResponse(
                            id = 1L,
                            channelId = 10L,
                            userId = 50L,
                            joinedAt = LocalDateTime.now(),
                        )
                        every { addChannelMemberService.execute(any(), any(), any()) } returns response

                        controller.addMember(
                            userId = 1L,
                            channelId = 10L,
                            request = AddMemberRequest(userId = 50L),
                        )

                        verify(exactly = 1) {
                            addChannelMemberService.execute(1L, 10L, AddMemberRequest(userId = 50L))
                        }
                    }
                }
            }

            describe("removeMember 메서드는") {
                context("X-User-Id 헤더가 주어진 경우") {
                    it("요청자 userId와 경로의 memberId를 그대로 서비스에 전달한다") {
                        controller.removeMember(userId = 1L, channelId = 10L, memberId = 50L)

                        verify(exactly = 1) { removeChannelMemberService.execute(1L, 10L, 50L) }
                    }
                }
            }
        }
    })

package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelArchivePermissionSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class ArchiveChannelServiceImplTest :
    DescribeSpec({

        lateinit var channel: Channel
        lateinit var permissionSupport: ChannelArchivePermissionSupport
        lateinit var channelEventPublisher: ChannelEventPublisher
        lateinit var archiveService: ArchiveChannelServiceImpl
        lateinit var unarchiveService: UnarchiveChannelServiceImpl

        beforeEach {
            channel = Channel(
                id = 1L,
                teamId = 10L,
                name = "general",
                type = ChannelType.TEXT,
                viewType = ChannelViewType.TEXT,
                description = null,
                createdBy = 1L,
            )
            val channelRepository = mockk<ChannelRepository> { every { findByIdForUpdate(1L) } returns channel }
            permissionSupport = mockk { every { requireArchivePermission(channel, 7L) } just runs }
            channelEventPublisher = mockk(relaxed = true)
            val channelAccessGuard = ChannelAccessGuard(channelRepository)
            archiveService = ArchiveChannelServiceImpl(channelAccessGuard, permissionSupport, channelEventPublisher)
            unarchiveService = UnarchiveChannelServiceImpl(channelAccessGuard, permissionSupport, channelEventPublisher)
        }

        describe("ArchiveChannelServiceImpl 클래스의 execute 메서드는") {
            context("보관 권한이 있고 활성 채널이면") {
                it("채널을 보관하고 보관 상태를 이벤트로 발행한다") {
                    val response = archiveService.execute(7L, 1L)

                    response.archived shouldBe true
                    response.archivedBy shouldBe 7L
                    verify { channelEventPublisher.publishUpdated(channel, any()) }
                }
            }

            context("이미 보관된 채널이면") {
                it("CONFLICT로 거부하고 이벤트를 발행하지 않는다") {
                    channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))

                    val error = shouldThrow<ExpectedException> { archiveService.execute(7L, 1L) }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
                }
            }

            context("보관 권한이 없으면") {
                it("채널 상태를 바꾸지 않는다") {
                    every { permissionSupport.requireArchivePermission(channel, 7L) } throws
                        ExpectedException("프로젝트 채널 보관 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    shouldThrow<ExpectedException> { archiveService.execute(7L, 1L) }

                    channel.isArchived shouldBe false
                    verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
                }
            }
        }

        describe("UnarchiveChannelServiceImpl 클래스의 execute 메서드는") {
            context("보관된 채널이면") {
                it("채널을 복원하고 이벤트로 발행한다") {
                    channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))

                    val response = unarchiveService.execute(7L, 1L)

                    response.archived shouldBe false
                    verify { channelEventPublisher.publishUpdated(channel, any()) }
                }
            }

            context("보관되지 않은 채널이면") {
                it("CONFLICT로 거부한다") {
                    val error = shouldThrow<ExpectedException> { unarchiveService.execute(7L, 1L) }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }
    })

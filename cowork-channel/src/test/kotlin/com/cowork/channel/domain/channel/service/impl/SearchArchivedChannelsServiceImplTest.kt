package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class SearchArchivedChannelsServiceImplTest :
    DescribeSpec({

        lateinit var channelRepository: ChannelRepository
        lateinit var evaluator: ChannelMessageReadPolicyEvaluator
        lateinit var service: SearchArchivedChannelsServiceImpl

        fun archived(id: Long, minute: Int) = Channel(
            id = id,
            teamId = 10L,
            projectId = 5L,
            name = "release-$id",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            createdBy = 1L,
        ).apply { archive(1L, LocalDateTime.of(2026, 11, 10, 9, minute)) }

        beforeEach {
            channelRepository = mockk()
            evaluator = mockk()
            val teamPermission = mockk<TeamPermissionService> { every { requireTeamMember(10L, 7L) } just runs }
            service = SearchArchivedChannelsServiceImpl(channelRepository, teamPermission, evaluator)
        }

        describe("SearchArchivedChannelsServiceImpl 클래스의 execute 메서드는") {
            context("조건에 맞는 보관 채널 중 일부만 읽을 수 있으면") {
                it("읽을 수 있는 채널만 반환하고 이스케이프한 이름 검색어로 조회한다") {
                    val readable = archived(1L, 0)
                    val hidden = archived(2L, 1)
                    every {
                        channelRepository.searchArchived(10L, null, 5L, "50!%", null, null, Limit.of(50))
                    } returns listOf(readable, hidden)
                    every { evaluator.filterReadable(10L, 7L, listOf(readable, hidden)) } returns listOf(readable)

                    val result = service.execute(7L, 10L, null, 5L, " 50% ", null, 50)

                    result.channels.map { it.id } shouldBe listOf(1L)
                    result.nextCursor.shouldBeNull()
                }
            }

            context("조회한 행 수가 요청한 크기와 같으면") {
                it("권한 필터 전의 마지막 행으로 다음 커서를 만들고 그 커서로 이어서 조회한다") {
                    val first = archived(3L, 2)
                    val last = archived(2L, 1)
                    every {
                        channelRepository.searchArchived(10L, null, null, null, null, null, Limit.of(2))
                    } returns listOf(first, last)
                    every { evaluator.filterReadable(10L, 7L, any()) } returns emptyList()

                    val cursor = service.execute(7L, 10L, null, null, null, null, 2).nextCursor.shouldNotBeNull()

                    every { channelRepository.searchArchived(any(), any(), any(), any(), any(), any(), any()) } returns
                        emptyList()
                    service.execute(7L, 10L, null, null, null, cursor, 2)

                    verify {
                        channelRepository.searchArchived(10L, null, null, null, last.archivedAt, 2L, Limit.of(2))
                    }
                }
            }

            context("조회 크기가 범위를 벗어나거나 커서 형식이 잘못되면") {
                it("BAD_REQUEST로 거부한다") {
                    shouldThrow<ExpectedException> { service.execute(7L, 10L, null, null, null, null, 0) }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                    shouldThrow<ExpectedException> { service.execute(7L, 10L, null, null, null, null, 101) }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                    shouldThrow<ExpectedException> { service.execute(7L, 10L, null, null, null, "not-a-cursor", 50) }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }
    })

package com.cowork.team.domain.team.presentation.controller

import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.CreateTeamService
import com.cowork.team.domain.team.service.DeleteTeamIconService
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.QueryMyTeamsService
import com.cowork.team.domain.team.service.QueryTeamService
import com.cowork.team.domain.team.service.UpdateTeamIconService
import com.cowork.team.domain.team.service.UpdateTeamService
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime

class TeamControllerTest :
    DescribeSpec({

        lateinit var generateIconPresignedUrlService: GenerateIconPresignedUrlService
        lateinit var confirmIconUploadService: ConfirmIconUploadService
        lateinit var createTeamService: CreateTeamService
        lateinit var queryMyTeamsService: QueryMyTeamsService
        lateinit var queryTeamService: QueryTeamService
        lateinit var updateTeamService: UpdateTeamService
        lateinit var updateTeamIconService: UpdateTeamIconService
        lateinit var deleteTeamIconService: DeleteTeamIconService
        lateinit var deleteTeamService: DeleteTeamService
        lateinit var controller: TeamController

        beforeEach {
            generateIconPresignedUrlService = mockk()
            confirmIconUploadService = mockk()
            createTeamService = mockk()
            queryMyTeamsService = mockk()
            queryTeamService = mockk()
            updateTeamService = mockk()
            updateTeamIconService = mockk()
            deleteTeamIconService = mockk(relaxed = true)
            deleteTeamService = mockk(relaxed = true)
            controller = TeamController(
                generateIconPresignedUrlService,
                confirmIconUploadService,
                createTeamService,
                queryMyTeamsService,
                queryTeamService,
                updateTeamService,
                updateTeamIconService,
                deleteTeamIconService,
                deleteTeamService,
            )
        }

        fun teamResponse(id: Long = 1L, ownerId: Long = 1L) = TeamResponse(
            id = id,
            name = "팀",
            description = null,
            iconUrl = null,
            ownerId = ownerId,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )

        describe("TeamController 클래스의") {
            describe("createTeam 메서드는") {
                context("X-User-Id 헤더로 전달된 사용자 ID를 받은 경우") {
                    it("헤더의 userId를 팀 소유자로 서비스에 그대로 전달한다") {
                        val ownerIdSlot = slot<Long>()
                        every { createTeamService.execute(capture(ownerIdSlot), any()) } returns teamResponse()

                        val request = CreateTeamRequest(name = "새 팀", description = null, iconUrl = null)
                        controller.createTeam(userId = 42L, request = request)

                        ownerIdSlot.captured shouldBe 42L
                        verify(exactly = 1) { createTeamService.execute(42L, request) }
                    }
                }
            }

            describe("getMyTeams 메서드는") {
                context("다른 X-User-Id 값이 전달될 때마다") {
                    it("매번 해당 userId로 서비스를 호출한다 — 헤더 값을 신뢰하여 그대로 전달") {
                        every { queryMyTeamsService.execute(any()) } returns emptyList<TeamSummaryResponse>()

                        controller.getMyTeams(userId = 7L)
                        controller.getMyTeams(userId = 999L)

                        verify(exactly = 1) { queryMyTeamsService.execute(7L) }
                        verify(exactly = 1) { queryMyTeamsService.execute(999L) }
                    }
                }
            }

            describe("getTeam 메서드는") {
                context("X-User-Id 헤더가 주어진 경우") {
                    it("헤더의 userId와 경로의 teamId를 그대로 서비스에 전달한다") {
                        every { queryTeamService.execute(any(), any()) } returns teamResponse(id = 5L)

                        controller.getTeam(userId = 1L, teamId = 5L)

                        verify(exactly = 1) { queryTeamService.execute(1L, 5L) }
                    }
                }
            }

            describe("updateTeam 메서드는") {
                context("X-User-Id 헤더가 주어진 경우") {
                    it("헤더의 userId를 수정 권한 판단의 기준으로 서비스에 전달한다") {
                        val request = UpdateTeamRequest(name = "수정된 팀", description = null, iconUrl = null)
                        every { updateTeamService.execute(any(), any(), any()) } returns teamResponse(id = 5L)

                        controller.updateTeam(userId = 3L, teamId = 5L, request = request)

                        verify(exactly = 1) { updateTeamService.execute(3L, 5L, request) }
                    }
                }
            }

            describe("deleteTeam 메서드는") {
                context("X-User-Id 헤더가 주어진 경우") {
                    it("바디 없이 헤더의 userId만으로 삭제 서비스를 호출한다") {
                        controller.deleteTeam(userId = 9L, teamId = 5L)

                        verify(exactly = 1) { deleteTeamService.execute(9L, 5L) }
                    }
                }
            }
        }
    })

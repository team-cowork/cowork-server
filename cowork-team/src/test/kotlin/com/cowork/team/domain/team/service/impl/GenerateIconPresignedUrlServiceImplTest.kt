package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GenerateIconPresignedUrlServiceImplTest :
    DescribeSpec({
        lateinit var teamAccessGuard: TeamAccessGuard
        lateinit var teamIconOwnershipManager: TeamIconOwnershipManager
        lateinit var service: GenerateIconPresignedUrlServiceImpl

        beforeEach {
            teamAccessGuard = mockk()
            teamIconOwnershipManager = mockk()
            service = GenerateIconPresignedUrlServiceImpl(teamAccessGuard, teamIconOwnershipManager)
        }

        describe("GenerateIconPresignedUrlServiceImpl 클래스의 execute 메서드는") {
            context("팀 관리 권한이 없으면") {
                it("업로드 URL을 발급하지 않는다") {
                    every {
                        teamAccessGuard.requirePermission(1L, 7L, "manage_team")
                    } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

                    shouldThrow<ExpectedException> { service.execute(7L, 1L, "image/png") }.statusCode shouldBe
                        HttpStatus.FORBIDDEN
                    verify(exactly = 0) { teamIconOwnershipManager.issue(any(), any(), any()) }
                }
            }
            context("팀 관리 권한이 있으면") {
                it("해당 팀으로 업로드 URL을 발급한다") {
                    val response = IconPresignedUrlResponse(uploadUrl = "https://upload", objectKey = "key")
                    every { teamAccessGuard.requirePermission(1L, 7L, "manage_team") } returns Unit
                    every { teamIconOwnershipManager.issue(7L, 1L, "image/png") } returns response

                    service.execute(7L, 1L, "image/png") shouldBe response
                }
            }
        }
    })

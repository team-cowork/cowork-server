package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.TeamMemberships
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QueryMyTeamsServiceImplTest :
    DescribeSpec({
        describe("QueryMyTeamsServiceImpl 클래스의 execute 메서드는") {
            context("여러 팀의 멤버십이 있고 그중 한 팀이 삭제됐으면") {
                it("존재하는 팀에 사용자의 역할을 연결한다") {
                    val memberships = mockk<TeamMemberships>()
                    val teams = mockk<TeamRepository>()
                    every { memberships.rolesByTeam(10L) } returns mapOf(5L to "OWNER", 4L to "ADMIN", 3L to "MEMBER")
                    every { teams.findAllById(setOf(5L, 4L, 3L)) } returns listOf(
                        Team(id = 3L, name = "개발", description = null, iconUrl = null, ownerId = 20L),
                        Team(id = 5L, name = "기획", description = null, iconUrl = null, ownerId = 10L),
                    )

                    val result = QueryMyTeamsServiceImpl(memberships, teams).execute(10L)

                    result.map { it.name to it.myRole } shouldBe listOf("기획" to "OWNER", "개발" to "MEMBER")
                }
            }
        }
    })

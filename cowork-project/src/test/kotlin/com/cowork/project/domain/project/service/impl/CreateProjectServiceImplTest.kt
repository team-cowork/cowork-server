package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateProjectServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, mockk<ProjectAccessProjectionRepository>(relaxed = true), teamMembershipRepository, mockk(relaxed = true), mockk(relaxed = true))

    private val service =
        CreateProjectServiceImpl(
            projectRepository,
            projectEventPublisher,
            projectAccessGuard,
        )

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Nested
    inner class Execute {
        @Test
        fun `createProject은 팀 멤버 아니면 FORBIDDEN`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            verify(exactly = 0) { projectRepository.save(any()) }
        }

        @Test
        fun `createProject은 마지막 position 다음에 만들고 권한 기반 접근 목록 갱신을 요청한다`() {
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L, "OWNER")
            every { projectRepository.findMaxPositionByTeamId(100L) } returns 3
            every { projectRepository.save(any()) } answers { firstArg() }

            val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

            assertEquals(4, response.position)
            assertEquals(false, response.isPrivate)
        }
    }
}

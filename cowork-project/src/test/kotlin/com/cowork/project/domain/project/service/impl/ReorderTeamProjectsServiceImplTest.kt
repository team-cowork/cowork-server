package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ReorderTeamProjectsServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)

    private val service = ReorderTeamProjectsServiceImpl(projectRepository, projectAccessGuard, projectEventPublisher)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Test
    fun `reorderTeamProjects는 요청 순서대로 position을 갱신함`() {
        val first = project(id = 1L, position = 0)
        val second = project(id = 2L, position = 1)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(first, second)

        val result = service.execute(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
        verify(exactly = 1) { projectEventPublisher.publishUpdated(second, any()) }
        verify(exactly = 1) { projectEventPublisher.publishUpdated(first, any()) }
    }

    @Test
    fun `reorderTeamProjects는 팀 프로젝트 ID 누락 시 BAD_REQUEST`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns
            listOf(project(id = 1L), project(id = 2L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}

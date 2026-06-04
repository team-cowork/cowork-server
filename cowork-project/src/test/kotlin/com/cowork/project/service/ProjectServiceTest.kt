package com.cowork.project.service

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.TeamMembership
import com.cowork.project.dto.AddProjectMemberRequest
import com.cowork.project.dto.CreateProjectRequest
import com.cowork.project.dto.UpdateProjectRequest
import com.cowork.project.event.ProjectEventPublisher
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.repository.TeamMembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class ProjectServiceTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)

    private val service = ProjectService(projectRepository, projectMemberRepository, teamMembershipRepository, projectEventPublisher)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Test
    fun `createProject은 팀 멤버 아니면 FORBIDDEN`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.createProject(7L, CreateProjectRequest(teamId = 100L, name = "p", description = null))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { projectRepository.save(any()) }
    }

    @Test
    fun `createProject은 마지막 position 다음 값으로 저장`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findMaxPositionByTeamId(100L) } returns 3
        every { projectRepository.save(any()) } answers { firstArg() }
        every { projectMemberRepository.save(any()) } answers { firstArg() }

        val response = service.createProject(7L, CreateProjectRequest(teamId = 100L, name = "p", description = null))

        assertEquals(4, response.position)
    }

    @Test
    fun `reorderTeamProjects는 요청 순서대로 position을 갱신함`() {
        val first = project(id = 1L, position = 0)
        val second = project(id = 2L, position = 1)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(first, second)

        val result = service.reorderTeamProjects(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
    }

    @Test
    fun `reorderTeamProjects는 팀 프로젝트 ID 누락 시 BAD_REQUEST`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(project(id = 1L), project(id = 2L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.reorderTeamProjects(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateProject은 팀 OWNER 등가 권한으로 통과`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L, "OWNER")

        val response = service.updateProject(99L, 1L, UpdateProjectRequest(name = "newName"))
        assertEquals("newName", response.name)
    }

    @Test
    fun `updateProject은 팀 비멤버이면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateProject(99L, 1L, UpdateProjectRequest(name = "x"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddProjectMemberRequest(userId = 50L, role = "EDITOR"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}

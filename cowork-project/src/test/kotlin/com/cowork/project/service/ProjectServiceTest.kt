package com.cowork.project.service

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.TeamMembership
import com.cowork.project.dto.AddProjectMemberRequest
import com.cowork.project.dto.CreateProjectRequest
import com.cowork.project.dto.UpdateProjectRequest
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

    private val service = ProjectService(projectRepository, projectMemberRepository, teamMembershipRepository)

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

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

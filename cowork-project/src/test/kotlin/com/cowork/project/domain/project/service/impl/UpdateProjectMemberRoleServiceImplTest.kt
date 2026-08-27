package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.support.ProjectMemberLookupSupport
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.presentation.data.request.UpdateProjectMemberRoleReqDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class UpdateProjectMemberRoleServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))
    private val projectMemberLookupSupport = ProjectMemberLookupSupport(projectMemberRepository)

    private val service = UpdateProjectMemberRoleServiceImpl(projectAccessGuard, projectMemberLookupSupport)

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    @Test
    fun `updateRole은 대상 멤버가 다른 프로젝트 소속이면 BAD_REQUEST`() {
        every { projectRepository.findById(1L) } returns Optional.of(project())
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(10L) } returns
            Optional.of(ProjectMember(id = 10L, projectId = 2L, userId = 50L, role = ProjectMemberRole.VIEWER))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, 10L, UpdateProjectMemberRoleReqDto(role = ProjectMemberRole.EDITOR))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateRole은 변경하려는 역할이 OWNER면 BAD_REQUEST`() {
        every { projectRepository.findById(1L) } returns Optional.of(project())
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(10L) } returns
            Optional.of(ProjectMember(id = 10L, projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, 10L, UpdateProjectMemberRoleReqDto(role = ProjectMemberRole.OWNER))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateRole은 대상 멤버가 OWNER면 BAD_REQUEST`() {
        every { projectRepository.findById(1L) } returns Optional.of(project())
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(10L) } returns
            Optional.of(ProjectMember(id = 10L, projectId = 1L, userId = 50L, role = ProjectMemberRole.OWNER))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, 10L, UpdateProjectMemberRoleReqDto(role = ProjectMemberRole.EDITOR))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateRole은 성공하면 멤버의 역할을 변경한다`() {
        every { projectRepository.findById(1L) } returns Optional.of(project())
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        val member = ProjectMember(id = 10L, projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { projectMemberRepository.findById(10L) } returns Optional.of(member)

        val response = service.execute(99L, 1L, 10L, UpdateProjectMemberRoleReqDto(role = ProjectMemberRole.EDITOR))

        assertEquals(ProjectMemberRole.EDITOR, response.role)
        assertEquals(ProjectMemberRole.EDITOR, member.role)
    }
}

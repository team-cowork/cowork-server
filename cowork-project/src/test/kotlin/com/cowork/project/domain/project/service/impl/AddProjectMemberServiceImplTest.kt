package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class AddProjectMemberServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))

    private val service =
        AddProjectMemberServiceImpl(projectMemberRepository, projectAccessGuard, projectMemberEventPublisher)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    @Nested
    inner class Execute {
        @Test
        fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
                ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = ProjectMemberRole.EDITOR))
            }
            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }

        @Test
        fun `addMember는 팀 멤버를 요청한 역할로 추가한다`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
                ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
            every { projectMemberRepository.findByProjectIdAndUserIdForUpdate(1L, 50L) } returns null
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns
                TeamMembership(teamId = 100L, userId = 50L, role = "MEMBER")
            every { projectMemberRepository.save(any()) } answers { firstArg() }

            val result = service.execute(
                1L,
                1L,
                AddProjectMemberReqDto(userId = 50L, role = ProjectMemberRole.EDITOR),
            )

            assertEquals(50L, result.userId)
            assertEquals(ProjectMemberRole.EDITOR, result.role)
        }

        @Test
        fun `addMember는 OWNER 역할 직접 부여를 거부한다`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
                ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns
                TeamMembership(teamId = 100L, userId = 50L, role = "MEMBER")

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = ProjectMemberRole.OWNER))
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
            verify(exactly = 0) { projectMemberRepository.save(any()) }
        }

        @Test
        fun `addMember는 이미 참여 중인 사용자를 중복 추가하지 않는다`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
                ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns
                TeamMembership(teamId = 100L, userId = 50L, role = "MEMBER")
            every { projectMemberRepository.findByProjectIdAndUserIdForUpdate(1L, 50L) } returns
                ProjectMember(projectId = 1L, userId = 50L)

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = ProjectMemberRole.EDITOR))
            }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            verify(exactly = 0) { projectMemberRepository.save(any()) }
        }
    }
}

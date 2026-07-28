package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.support.ProjectEnumParser
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class AddProjectMemberServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository)

    private val service =
        AddProjectMemberServiceImpl(projectMemberRepository, projectAccessGuard, ProjectEnumParser(), projectMemberEventPublisher)

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    @Test
    fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = "EDITOR"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `addMember는 성공 시 트랜잭션 커밋 후 멤버 추가 이벤트를 발행한다`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns null
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns
            TeamMembership(teamId = 100L, userId = 50L, role = "MEMBER")
        every { projectMemberRepository.save(any()) } answers { firstArg() }

        service.execute(1L, 1L, AddProjectMemberReqDto(userId = 50L, role = "EDITOR"))
        verify(exactly = 0) { projectMemberEventPublisher.publishAdded(any(), any()) }

        TransactionSynchronizationUtils.triggerAfterCommit()

        verify(exactly = 1) { projectMemberEventPublisher.publishAdded(1L, 50L) }
    }
}

package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.support.ProjectMemberLookupSupport
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
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

class RemoveProjectMemberServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository)
    private val projectMemberLookupSupport = ProjectMemberLookupSupport(projectMemberRepository)

    private val service = RemoveProjectMemberServiceImpl(
        projectMemberRepository,
        projectAccessGuard,
        projectMemberLookupSupport,
        projectMemberEventPublisher,
    )

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = 0, createdBy = 1L)

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `removeMember는 다른 프로젝트의 멤버면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(5L) } returns
            Optional.of(ProjectMember(id = 5L, projectId = 999L, userId = 50L, role = ProjectMemberRole.EDITOR))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, 5L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { projectMemberEventPublisher.publishRemoved(any(), any()) }
    }

    @Test
    fun `removeMember는 OWNER 제거 시도 시 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(5L) } returns
            Optional.of(ProjectMember(id = 5L, projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, 5L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { projectMemberEventPublisher.publishRemoved(any(), any()) }
    }

    @Test
    fun `removeMember는 성공 시 트랜잭션 커밋 후 멤버 제거 이벤트를 발행한다`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 1L) } returns
            ProjectMember(projectId = 1L, userId = 1L, role = ProjectMemberRole.OWNER)
        every { projectMemberRepository.findById(5L) } returns
            Optional.of(ProjectMember(id = 5L, projectId = 1L, userId = 50L, role = ProjectMemberRole.EDITOR))

        service.execute(1L, 1L, 5L)
        verify(exactly = 0) { projectMemberEventPublisher.publishRemoved(any(), any()) }

        TransactionSynchronizationUtils.triggerAfterCommit()

        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(1L, 50L) }
    }
}

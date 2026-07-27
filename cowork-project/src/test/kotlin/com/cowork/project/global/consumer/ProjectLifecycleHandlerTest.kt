package com.cowork.project.global.consumer

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

class ProjectLifecycleHandlerTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>(relaxed = true)
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)

    private val handler = ProjectLifecycleHandler(
        projectRepository,
        projectMemberRepository,
        teamMembershipRepository,
        projectMemberEventPublisher,
    )

    init {
        TransactionSynchronizationManager.clear()
    }

    private fun project(id: Long, teamId: Long) =
        Project(id = id, teamId = teamId, name = "p$id", description = null, createdBy = 1L)

    @Test
    fun `onTeamDeleted는 팀의 모든 프로젝트를 삭제하고 멤버 캐시 무효화 이벤트를 발행한다`() {
        val projects = listOf(project(1L, 100L), project(2L, 100L))
        every { projectRepository.findAllByTeamId(100L) } returns projects
        every { projectRepository.deleteAll(projects) } just Runs
        every { projectMemberRepository.findAllByProjectIdIn(listOf(1L, 2L)) } returns listOf(
            ProjectMember(projectId = 1L, userId = 7L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 2L, userId = 8L, role = ProjectMemberRole.EDITOR),
        )

        handler.onTeamDeleted(100L)

        verify(exactly = 1) { projectRepository.deleteAll(projects) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(1L, 7L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(2L, 8L) }
    }

    @Test
    fun `onTeamDeleted는 대상 없으면 no-op`() {
        every { projectRepository.findAllByTeamId(100L) } returns emptyList()

        handler.onTeamDeleted(100L)

        verify(exactly = 0) { projectRepository.deleteAll(any<List<Project>>()) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 OWNER인 프로젝트는 삭제, 나머지는 멤버십만 제거하며 각각 캐시 무효화 이벤트를 발행한다`() {
        every { projectRepository.findIdsByTeamId(100L) } returns listOf(1L, 2L, 3L)
        every {
            projectMemberRepository.findAllByUserIdAndRoleAndProjectIdIn(
                7L,
                ProjectMemberRole.OWNER,
                listOf(1L, 2L, 3L),
            )
        } returns listOf(ProjectMember(projectId = 2L, userId = 7L, role = ProjectMemberRole.OWNER))
        every { projectMemberRepository.findAllByProjectIdIn(listOf(2L)) } returns listOf(
            ProjectMember(projectId = 2L, userId = 7L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 2L, userId = 9L, role = ProjectMemberRole.EDITOR),
        )

        handler.onMemberRemovedFromTeam(100L, 7L)

        verify(exactly = 1) { projectRepository.deleteAllById(listOf(2L)) }
        verify(exactly = 1) { projectMemberRepository.deleteAllByUserIdAndProjectIdIn(7L, listOf(1L, 3L)) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(2L, 7L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(2L, 9L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(1L, 7L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(3L, 7L) }
    }

    @Test
    fun `onUserDeleted는 유저가 OWNER인 프로젝트 삭제 + 모든 멤버십 제거하며 캐시 무효화 이벤트를 발행한다`() {
        every {
            projectMemberRepository.findAllByUserId(50L)
        } returns listOf(
            ProjectMember(projectId = 10L, userId = 50L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 11L, userId = 50L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 12L, userId = 50L, role = ProjectMemberRole.EDITOR),
        )
        every { projectMemberRepository.findAllByProjectIdIn(listOf(10L, 11L)) } returns listOf(
            ProjectMember(projectId = 10L, userId = 50L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 11L, userId = 50L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 10L, userId = 60L, role = ProjectMemberRole.VIEWER),
        )

        handler.onUserDeleted(50L)

        verify(exactly = 1) { projectRepository.deleteAllById(listOf(10L, 11L)) }
        verify(exactly = 1) { projectMemberRepository.deleteAllByUserId(50L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(10L, 50L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(11L, 50L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(10L, 60L) }
        verify(exactly = 1) { projectMemberEventPublisher.publishRemoved(12L, 50L) }
    }
}

package com.cowork.project.consumer

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ProjectLifecycleHandlerTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)

    private val handler = ProjectLifecycleHandler(projectRepository, projectMemberRepository)

    private fun project(id: Long, teamId: Long) =
        Project(id = id, teamId = teamId, name = "p$id", description = null, createdBy = 1L)

    @Test
    fun `onTeamDeleted는 팀의 모든 프로젝트를 삭제`() {
        val projects = listOf(project(1L, 100L), project(2L, 100L))
        every { projectRepository.findAllByTeamId(100L) } returns projects
        every { projectRepository.deleteAll(projects) } just Runs

        handler.onTeamDeleted(100L)

        verify(exactly = 1) { projectRepository.deleteAll(projects) }
    }

    @Test
    fun `onTeamDeleted는 대상 없으면 no-op`() {
        every { projectRepository.findAllByTeamId(100L) } returns emptyList()

        handler.onTeamDeleted(100L)

        verify(exactly = 0) { projectRepository.deleteAll(any<List<Project>>()) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 OWNER인 프로젝트는 삭제, 나머지는 멤버십만 제거`() {
        val projects = listOf(project(1L, 100L), project(2L, 100L), project(3L, 100L))
        every { projectRepository.findAllByTeamId(100L) } returns projects
        every {
            projectMemberRepository.findAllByUserIdAndRoleAndProjectIdIn(
                7L, ProjectMemberRole.OWNER, listOf(1L, 2L, 3L),
            )
        } returns listOf(ProjectMember(projectId = 2L, userId = 7L, role = ProjectMemberRole.OWNER))

        handler.onMemberRemovedFromTeam(100L, 7L)

        verify(exactly = 1) { projectRepository.deleteAllById(listOf(2L)) }
        verify(exactly = 1) { projectMemberRepository.deleteAllByUserIdAndProjectIdIn(7L, listOf(1L, 3L)) }
    }

    @Test
    fun `onUserDeleted는 유저가 OWNER인 프로젝트 삭제 + 모든 멤버십 제거`() {
        every {
            projectMemberRepository.findAllByUserIdAndRole(50L, ProjectMemberRole.OWNER)
        } returns listOf(
            ProjectMember(projectId = 10L, userId = 50L, role = ProjectMemberRole.OWNER),
            ProjectMember(projectId = 11L, userId = 50L, role = ProjectMemberRole.OWNER),
        )

        handler.onUserDeleted(50L)

        verify(exactly = 1) { projectRepository.deleteAllById(listOf(10L, 11L)) }
        verify(exactly = 1) { projectMemberRepository.deleteAllByUserId(50L) }
    }
}

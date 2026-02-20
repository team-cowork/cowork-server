package com.cowork.project

import com.cowork.project.domain.Project
import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.service.ProjectTeamLifecycleListener
import com.cowork.team.event.TeamDeletedEvent
import com.cowork.team.event.TeamMemberRemovedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class ProjectTeamLifecycleListenerTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val listener = ProjectTeamLifecycleListener(projectRepository, projectMemberRepository, eventPublisher)

    private fun project(id: Long) =
        Project(teamId = 1, name = "p$id", description = null, githubRepoUrl = null, position = 0, createdBy = 1, id = id)

    @Test
    fun `팀을 삭제하면 프로젝트 삭제 이벤트를 먼저 발행하고 프로젝트를 지운다`() {
        val projects = listOf(project(10), project(11))
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(1) } returns projects

        listener.onTeamDeleted(TeamDeletedEvent(1))

        verifyOrder {
            eventPublisher.publishEvent(ProjectDeletedEvent(10, 1))
            eventPublisher.publishEvent(ProjectDeletedEvent(11, 1))
            projectMemberRepository.deleteAllByProjectIdIn(listOf(10, 11))
            projectRepository.deleteAll(projects)
        }
    }

    @Test
    fun `프로젝트가 없는 팀은 아무것도 지우지 않는다`() {
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(1) } returns emptyList()

        listener.onTeamDeleted(TeamDeletedEvent(1))

        verify(exactly = 0) { projectRepository.deleteAll(any<Iterable<Project>>()) }
    }

    @Test
    fun `팀에서 나간 사용자는 팀의 모든 프로젝트에서 제외한다`() {
        every { projectRepository.findIdsByTeamId(1) } returns listOf(10, 11)

        listener.onTeamMemberRemoved(TeamMemberRemovedEvent(1, 5))

        verify { projectMemberRepository.deleteAllByUserIdAndProjectIdIn(5, listOf(10, 11)) }
    }
}

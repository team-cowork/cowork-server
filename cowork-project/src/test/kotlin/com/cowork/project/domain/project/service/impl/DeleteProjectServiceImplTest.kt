package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class DeleteProjectServiceImplTest {
    private val projectRepository = mockk<ProjectRepository>()
    private val projectMemberRepository = mockk<ProjectMemberRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectAccessGuard = mockk<ProjectAccessGuard>()
    private val service =
        DeleteProjectServiceImpl(
            projectRepository,
            projectMemberRepository,
            projectEventPublisher,
            projectMemberEventPublisher,
            projectAccessGuard,
        )

    @Test
    fun `deleteProject는 현재 트랜잭션에서 모든 멤버 제거 이벤트를 프로젝트 삭제보다 먼저 기록`() {
        val project = Project(id = 7L, teamId = 3L, name = "Backend", description = null, createdBy = 11L)
        val members = listOf(
            ProjectMember(projectId = 7L, userId = 11L),
            ProjectMember(projectId = 7L, userId = 12L),
        )
        every { projectAccessGuard.findProjectOrThrow(7L) } returns project
        every { projectAccessGuard.requireProjectOwner(project, 11L) } just Runs
        every { projectMemberRepository.findByProjectId(7L) } returns members
        every { projectRepository.delete(project) } just Runs

        service.execute(11L, 7L)

        verifyOrder {
            projectMemberEventPublisher.publishRemoved(7L, 11L, any(), false)
            projectMemberEventPublisher.publishRemoved(7L, 12L, any(), false)
            projectEventPublisher.publishDeleted(project, any())
        }
    }
}

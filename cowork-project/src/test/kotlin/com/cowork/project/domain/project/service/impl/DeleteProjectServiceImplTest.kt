package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeleteProjectServiceImplTest {
    private val projectRepository = mockk<ProjectRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectAccessGuard = mockk<ProjectAccessGuard>()
    private val repoDeletionSupport = mockk<ProjectGithubRepoDeletionSupport>(relaxed = true)
    private val service =
        DeleteProjectServiceImpl(
            projectRepository,
            projectEventPublisher,
            projectAccessGuard,
            repoDeletionSupport,
        )

    @Nested
    inner class Execute {
        @Test
        fun `deleteProject는 OWNER가 요청하면 프로젝트를 삭제한다`() {
            val project = Project(id = 7L, teamId = 3L, name = "Backend", description = null, createdBy = 11L)
            every { projectAccessGuard.findProjectForUpdateOrThrow(7L) } returns project
            every { projectAccessGuard.requireProjectOwner(project, 11L) } just Runs
            every { projectRepository.delete(project) } just Runs

            service.execute(11L, 7L)

            verify(exactly = 1) { projectRepository.delete(project) }
        }
    }
}

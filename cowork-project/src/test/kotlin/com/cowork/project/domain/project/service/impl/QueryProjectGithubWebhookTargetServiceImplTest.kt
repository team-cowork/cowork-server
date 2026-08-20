package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class QueryProjectGithubWebhookTargetServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>()
    private val service = QueryProjectGithubWebhookTargetServiceImpl(projectRepository)

    private fun project(id: Long = 1L, teamId: Long = 100L, channelId: Long? = 10L) =
        Project(
            id = id,
            teamId = teamId,
            name = "p",
            description = null,
            createdBy = 1L,
            githubRepoUrl = "https://github.com/my-org/my-repo",
        ).apply { channelId?.let { setGithubWebhookChannel(it) } }

    @Test
    fun `getWebhookTarget은 알림 채널이 설정된 프로젝트가 있으면 대상 정보를 반환`() {
        every { projectRepository.findByGithubRepoUrl("https://github.com/my-org/my-repo") } returns project()

        val result = service.execute("my-org", "my-repo")

        assertEquals(100L, result.teamId)
        assertEquals(1L, result.projectId)
        assertEquals(10L, result.channelId)
    }

    @Test
    fun `getWebhookTarget은 연결된 프로젝트가 없으면 NOT_FOUND`() {
        every { projectRepository.findByGithubRepoUrl("https://github.com/my-org/my-repo") } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute("my-org", "my-repo")
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `getWebhookTarget은 알림 채널이 설정되지 않았으면 NOT_FOUND`() {
        every { projectRepository.findByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            project(channelId = null)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute("my-org", "my-repo")
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}

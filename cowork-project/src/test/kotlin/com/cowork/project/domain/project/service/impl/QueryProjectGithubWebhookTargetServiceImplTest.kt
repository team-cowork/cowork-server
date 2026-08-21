package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        every { projectRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(project())

        val result = service.execute("my-org", "my-repo")

        assertEquals(1, result.size)
        assertEquals(100L, result[0].teamId)
        assertEquals(1L, result[0].projectId)
        assertEquals(10L, result[0].channelId)
    }

    @Test
    fun `getWebhookTarget은 연결된 프로젝트가 없으면 빈 목록`() {
        every { projectRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns emptyList()

        val result = service.execute("my-org", "my-repo")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWebhookTarget은 알림 채널이 설정되지 않은 프로젝트는 제외`() {
        every { projectRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(project(channelId = null))

        val result = service.execute("my-org", "my-repo")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWebhookTarget은 서로 다른 팀이 같은 레포를 연결해도 알림 채널이 설정된 것만 전부 반환`() {
        every { projectRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(
                project(id = 1L, teamId = 100L, channelId = 10L),
                project(id = 2L, teamId = 200L, channelId = null),
                project(id = 3L, teamId = 300L, channelId = 30L),
            )

        val result = service.execute("my-org", "my-repo")

        assertEquals(2, result.size)
        assertEquals(setOf(100L, 300L), result.map { it.teamId }.toSet())
    }
}

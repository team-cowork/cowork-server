package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryProjectGithubWebhookTargetServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>()
    private val service = QueryProjectGithubWebhookTargetServiceImpl(projectGithubRepoRepository)

    private fun repoLink(id: Long = 1L, projectId: Long = 1L, teamId: Long = 100L, channelId: Long? = 10L) =
        ProjectGithubRepo(
            id = id,
            projectId = projectId,
            teamId = teamId,
            githubRepoUrl = "https://github.com/my-org/my-repo",
        ).apply { channelId?.let { setWebhookChannel(it) } }

    @Test
    fun `getWebhookTarget은 알림 채널이 설정된 레포가 있으면 대상 정보를 반환`() {
        every { projectGithubRepoRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(repoLink())

        val result = service.execute("my-org", "my-repo")

        assertEquals(1, result.size)
        assertEquals(100L, result[0].teamId)
        assertEquals(1L, result[0].projectId)
        assertEquals(10L, result[0].channelId)
    }

    @Test
    fun `getWebhookTarget은 연결된 레포가 없으면 빈 목록`() {
        every { projectGithubRepoRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            emptyList()

        val result = service.execute("my-org", "my-repo")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWebhookTarget은 알림 채널이 설정되지 않은 레포는 제외`() {
        every { projectGithubRepoRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(repoLink(channelId = null))

        val result = service.execute("my-org", "my-repo")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWebhookTarget은 서로 다른 팀이 같은 레포를 연결해도 알림 채널이 설정된 것만 전부 반환`() {
        every { projectGithubRepoRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
            listOf(
                repoLink(id = 1L, projectId = 1L, teamId = 100L, channelId = 10L),
                repoLink(id = 2L, projectId = 2L, teamId = 200L, channelId = null),
                repoLink(id = 3L, projectId = 3L, teamId = 300L, channelId = 30L),
            )

        val result = service.execute("my-org", "my-repo")

        assertEquals(2, result.size)
        assertEquals(setOf(100L, 300L), result.map { it.teamId }.toSet())
    }
}

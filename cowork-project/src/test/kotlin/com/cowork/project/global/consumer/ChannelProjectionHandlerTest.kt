package com.cowork.project.global.consumer

import com.cowork.project.domain.channel.entity.ChannelProjection
import com.cowork.project.domain.channel.repository.ChannelProjectionRepository
import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelProjectionHandlerTest {

    private val channelRepository = mockk<ChannelProjectionRepository>(relaxed = true)
    private val repoLinkRepository = mockk<ProjectGithubRepoRepository>(relaxed = true)
    private val repoEventPublisher = mockk<ProjectGithubRepoEventPublisher>(relaxed = true)
    private val channels = mutableMapOf<Long, ChannelProjection>()
    private val handler = ChannelProjectionHandler(channelRepository, repoLinkRepository, repoEventPublisher)

    @BeforeEach
    fun setUp() {
        channels.clear()
        every { channelRepository.findByIdForUpdate(any()) } answers { channels[firstArg()] }
        every { channelRepository.save(any()) } answers {
            firstArg<ChannelProjection>().also { channels[it.channelId] = it }
        }
        every { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(any()) } returns emptyList()
        every { repoLinkRepository.save(any<ProjectGithubRepo>()) } answers { firstArg() }
    }

    private fun repoLink(projectId: Long, channelId: Long?) = ProjectGithubRepo(
        id = 9L,
        projectId = projectId,
        teamId = 1L,
        githubRepoUrl = "https://github.com/my-org/my-repo",
        githubWebhookChannelId = channelId,
    )

    @Test
    fun `신규 채널 이벤트는 projection을 새로 생성한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00.123456Z")

        handler.apply(channelId = 100L, projectId = 1L, deleted = false, occurredAt = occurredAt)

        assertEquals(1L, channels[100L]?.projectId)
        assertFalse(requireNotNull(channels[100L]).deleted)
        verify(exactly = 1) { channelRepository.save(any()) }
    }

    @Test
    fun `더 과거 버전 이벤트는 무시하고 dangling link 정리도 수행하지 않는다`() {
        val newer = Instant.parse("2026-08-26T03:00:10Z")
        val older = Instant.parse("2026-08-26T03:00:00Z")
        handler.apply(channelId = 100L, projectId = 1L, deleted = false, occurredAt = newer)
        // 최초 삽입은 accept되어 dangling link 조회가 한 번 발생한다.
        verify(exactly = 1) { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(100L) }

        handler.apply(channelId = 100L, projectId = 2L, deleted = false, occurredAt = older)

        assertEquals(1L, channels[100L]?.projectId)
        // 과거 버전 이벤트는 거부되어 추가 조회가 발생하지 않는다.
        verify(exactly = 1) { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(100L) }
    }

    @Test
    fun `채널이 삭제되면 연결된 모든 repo link의 webhook channel을 해제하고 upsert 이벤트를 발행한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        channels[100L] = ChannelProjection(
            channelId = 100L,
            projectId = 1L,
            deleted = false,
            sourceOccurredAt = occurredAt.minusSeconds(10),
        )
        val link = repoLink(projectId = 1L, channelId = 100L)
        every { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(100L) } returns listOf(link)

        handler.apply(channelId = 100L, projectId = null, deleted = true, occurredAt = occurredAt)

        assertTrue(requireNotNull(channels[100L]).deleted)
        assertNull(link.githubWebhookChannelId)
        verify(exactly = 1) { repoLinkRepository.save(link) }
        verify(exactly = 1) { repoEventPublisher.publishUpsert(link, any()) }
    }

    @Test
    fun `채널이 다른 프로젝트로 이동하면 이전 프로젝트를 가리키던 repo link만 해제한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        channels[100L] = ChannelProjection(
            channelId = 100L,
            projectId = 1L,
            deleted = false,
            sourceOccurredAt = occurredAt.minusSeconds(10),
        )
        val staleLink = repoLink(projectId = 1L, channelId = 100L)
        val currentLink = repoLink(projectId = 2L, channelId = 100L)
        every { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(100L) } returns
            listOf(staleLink, currentLink)

        handler.apply(channelId = 100L, projectId = 2L, deleted = false, occurredAt = occurredAt)

        assertNull(staleLink.githubWebhookChannelId)
        assertEquals(100L, currentLink.githubWebhookChannelId)
        verify(exactly = 1) { repoLinkRepository.save(staleLink) }
        verify(exactly = 0) { repoLinkRepository.save(currentLink) }
    }

    @Test
    fun `dangling link가 없으면 저장이나 이벤트 발행 없이 종료한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

        handler.apply(channelId = 100L, projectId = 1L, deleted = false, occurredAt = occurredAt)

        verify(exactly = 0) { repoEventPublisher.publishUpsert(any(), any()) }
    }

    @Test
    fun `같은 버전에 삭제에서 미삭제로 되돌리려는 이벤트는 거부되어 dangling link를 조회하지 않는다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        channels[100L] = ChannelProjection(
            channelId = 100L,
            projectId = 1L,
            deleted = true,
            sourceOccurredAt = occurredAt,
        )

        handler.apply(channelId = 100L, projectId = 1L, deleted = false, occurredAt = occurredAt)

        assertTrue(requireNotNull(channels[100L]).deleted)
        verify(exactly = 0) { channelRepository.save(any()) }
        verify(exactly = 0) { repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(any()) }
    }
}

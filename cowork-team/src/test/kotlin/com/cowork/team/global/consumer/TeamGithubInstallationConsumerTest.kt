package com.cowork.team.global.consumer

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamGithubInstallationConsumerTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamGithubStateSupport = mockk<TeamGithubStateSupport>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)

    private val consumer = TeamGithubInstallationConsumer(teamRepository, teamGithubStateSupport, teamEventPublisher)

    private fun team(id: Long = 100L) = Team(id = id, name = "team", description = null, iconUrl = null, ownerId = 1L)

    @Test
    fun `consumeConnected는 state가 유효하면 팀에 installation을 연결한다`() {
        val existing = team()
        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
        every { teamRepository.findByGithubInstallationId(1L) } returns null
        every { teamRepository.findByIdForUpdate(100L) } returns existing
        every { teamRepository.save(existing) } returns existing

        consumer.consumeConnected(TeamGithubConnectedPayload("valid-state", installationId = 1L, orgLogin = "my-org"))

        assertEquals(1L, existing.githubInstallationId)
        assertEquals("my-org", existing.githubOrgLogin)
        verify(exactly = 1) { teamRepository.save(existing) }
        verify(exactly = 1) { teamEventPublisher.publishUpdated(existing, 10L, any()) }
    }

    @Test
    fun `consumeConnected는 state 검증에 실패하면 무시한다`() {
        every { teamGithubStateSupport.verifyState("invalid-state") } throws
            ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

        consumer.consumeConnected(TeamGithubConnectedPayload("invalid-state", installationId = 1L, orgLogin = "my-org"))

        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
        verify(exactly = 0) { teamRepository.save(any()) }
    }

    @Test
    fun `consumeConnected는 installation이 이미 다른 팀에 연결돼 있으면 무시한다`() {
        val ownedByOtherTeam = team(id = 200L)
        every { teamGithubStateSupport.verifyState("valid-state") } returns (100L to 10L)
        every { teamRepository.findByGithubInstallationId(1L) } returns ownedByOtherTeam

        consumer.consumeConnected(TeamGithubConnectedPayload("valid-state", installationId = 1L, orgLogin = "my-org"))

        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
        verify(exactly = 0) { teamRepository.save(any()) }
    }

    @Test
    fun `consumeDisconnected는 installationId로 찾아서 연결을 해제한다`() {
        val existing = team()
        existing.connectGithub(installationId = 1L, orgLogin = "my-org")
        every { teamRepository.findByGithubInstallationId(1L) } returns existing
        every { teamRepository.findByIdForUpdate(100L) } returns existing
        every { teamRepository.save(existing) } returns existing

        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L))

        assertEquals(null, existing.githubInstallationId)
        verify(exactly = 1) { teamRepository.save(existing) }
        verify(exactly = 1) { teamEventPublisher.publishUpdated(existing, existing.ownerId, any()) }
    }
}

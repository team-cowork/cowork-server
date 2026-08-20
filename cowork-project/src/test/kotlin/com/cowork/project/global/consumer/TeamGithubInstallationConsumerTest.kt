package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.github.service.TeamGithubStateVerifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class TeamGithubInstallationConsumerTest {

    private val teamGithubInstallationRepository = mockk<TeamGithubInstallationRepository>(relaxed = true)
    private val teamGithubStateVerifier = mockk<TeamGithubStateVerifier>()

    private val consumer = TeamGithubInstallationConsumer(teamGithubInstallationRepository, teamGithubStateVerifier)

    @Test
    fun `consumeConnected는 기존 설치가 없으면 새로 생성한다`() {
        every { teamGithubStateVerifier.verifyState("valid-state") } returns 100L
        every { teamGithubInstallationRepository.findById(100L) } returns Optional.empty()
        every { teamGithubInstallationRepository.save(any()) } answers { firstArg() }

        consumer.consumeConnected(TeamGithubConnectedPayload("valid-state", installationId = 1L, orgLogin = "my-org"))

        verify(exactly = 1) {
            teamGithubInstallationRepository.save(
                match { it.teamId == 100L && it.installationId == 1L && it.orgLogin == "my-org" },
            )
        }
    }

    @Test
    fun `consumeConnected는 기존 설치가 있으면 갱신한다`() {
        val existing = TeamGithubInstallation(teamId = 100L, installationId = 1L, orgLogin = "old-org")
        every { teamGithubStateVerifier.verifyState("valid-state") } returns 100L
        every { teamGithubInstallationRepository.findById(100L) } returns Optional.of(existing)

        consumer.consumeConnected(TeamGithubConnectedPayload("valid-state", installationId = 2L, orgLogin = "new-org"))

        assertEquals(2L, existing.installationId)
        assertEquals("new-org", existing.orgLogin)
        verify(exactly = 0) { teamGithubInstallationRepository.save(any()) }
    }

    @Test
    fun `consumeConnected는 state 검증에 실패하면 무시한다`() {
        every { teamGithubStateVerifier.verifyState("invalid-state") } throws
            ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

        consumer.consumeConnected(TeamGithubConnectedPayload("invalid-state", installationId = 1L, orgLogin = "my-org"))

        verify(exactly = 0) { teamGithubInstallationRepository.save(any()) }
        verify(exactly = 0) { teamGithubInstallationRepository.findById(any()) }
    }

    @Test
    fun `consumeDisconnected는 installationId로 찾아서 삭제한다`() {
        val existing = TeamGithubInstallation(teamId = 100L, installationId = 1L, orgLogin = "my-org")
        every { teamGithubInstallationRepository.findByInstallationId(1L) } returns existing

        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L))

        verify(exactly = 1) { teamGithubInstallationRepository.delete(existing) }
    }

    @Test
    fun `consumeDisconnected는 해당 installationId가 없으면 no-op`() {
        every { teamGithubInstallationRepository.findByInstallationId(1L) } returns null

        consumer.consumeDisconnected(TeamGithubDisconnectedPayload(installationId = 1L))

        verify(exactly = 0) { teamGithubInstallationRepository.delete(any()) }
    }
}

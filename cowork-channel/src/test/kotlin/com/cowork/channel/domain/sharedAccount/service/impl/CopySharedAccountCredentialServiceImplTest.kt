package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountCredentialCopy
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.AccountCredentialCopyRepository
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class CopySharedAccountCredentialServiceImplTest {

    private val sharedAccountRepository = mockk<SharedAccountRepository>(relaxed = true)
    private val accountCredentialCopyRepository = mockk<AccountCredentialCopyRepository>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val credentialEncryptionService = mockk<CredentialEncryptionService>()
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val sharedAccountAccessGuard = SharedAccountAccessGuard()
    private val lookupSupport = SharedAccountLookupSupport(sharedAccountRepository, channelAccessGuard, teamPermissionService, credentialEncryptionService)

    private val service = CopySharedAccountCredentialServiceImpl(
        accountCredentialCopyRepository,
        channelAccessGuard,
        teamPermissionService,
        credentialEncryptionService,
        sharedAccountAccessGuard,
        lookupSupport,
    )

    private fun accountShareChannel(id: Long = 1L, teamId: Long = 100L, createdBy: Long = 1L) = Channel(
        id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
        isPrivate = false, position = 0, createdBy = createdBy, projectId = null,
    )

    private fun textChannel() = Channel(
        id = 1L, teamId = 100L, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1L, projectId = null,
    )

    private fun account(
        id: Long = 10L,
        channelId: Long = 1L,
        provider: AccountProvider = AccountProvider.GITHUB,
        credential: String? = "iv:ciphertext",
        createdBy: Long = 1L,
    ) = SharedAccount(
        id = id,
        channelId = channelId,
        provider = provider,
        providerLabel = null,
        accountIdentifier = "user",
        credential = credential,
        connectedViaOAuth = false,
        createdBy = createdBy,
    )

    @Test
    fun `copyCredential은 복호화된 credential을 반환하고 접근 로그를 저장함`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns account(credential = "encrypted")
        every { credentialEncryptionService.decrypt("encrypted") } returns "plainPassword"
        every { accountCredentialCopyRepository.save(any()) } answers { firstArg() }

        val result = service.copyCredential(1L, 1L, 10L)

        assertEquals("plainPassword", result)
        verify { accountCredentialCopyRepository.save(any<AccountCredentialCopy>()) }
    }

    @Test
    fun `copyCredential은 credential이 null이면 NOT_FOUND`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns account(credential = null)

        val ex = assertThrows<ExpectedException> { service.copyCredential(1L, 1L, 10L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `copyCredential은 팀 비멤버이면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows<ExpectedException> { service.copyCredential(7L, 1L, 10L) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `copyCredential은 ACCOUNT_SHARE 채널이 아니면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(textChannel())

        val ex = assertThrows<ExpectedException> { service.copyCredential(1L, 1L, 10L) }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}

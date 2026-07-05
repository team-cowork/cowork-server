package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class GetSharedAccountServiceImplTest {

    private val sharedAccountRepository = mockk<SharedAccountRepository>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val credentialEncryptionService = mockk<CredentialEncryptionService>()
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val sharedAccountAccessGuard = SharedAccountAccessGuard()
    private val lookupSupport = SharedAccountLookupSupport(sharedAccountRepository, channelAccessGuard, teamPermissionService, credentialEncryptionService)

    private val service = GetSharedAccountServiceImpl(channelAccessGuard, teamPermissionService, sharedAccountAccessGuard, lookupSupport)

    private fun accountShareChannel(id: Long = 1L, teamId: Long = 100L, createdBy: Long = 1L) = Channel(
        id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT,
        viewType = ChannelViewType.ACCOUNT_SHARE, description = null,
        isPrivate = false, position = 0, createdBy = createdBy, projectId = null,
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
    fun `getAccount는 계정이 없으면 NOT_FOUND`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(99L, 1L) } returns null

        val ex = assertThrows<ExpectedException> { service.getAccount(1L, 1L, 99L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `getAccount는 credential을 복호화 후 마스킹하여 반환함`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns account(credential = "encrypted")
        every { credentialEncryptionService.decrypt("encrypted") } returns "plainPassword"
        every { credentialEncryptionService.mask("plainPassword") } returns "••••word"

        val result = service.getAccount(1L, 1L, 10L)

        assertEquals("••••word", result.maskedCredential)
    }
}

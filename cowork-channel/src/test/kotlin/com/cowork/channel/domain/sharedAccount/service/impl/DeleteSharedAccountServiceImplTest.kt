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
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class DeleteSharedAccountServiceImplTest {

    private val sharedAccountRepository = mockk<SharedAccountRepository>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val credentialEncryptionService = mockk<CredentialEncryptionService>()
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val sharedAccountAccessGuard = SharedAccountAccessGuard()
    private val lookupSupport =
        SharedAccountLookupSupport(
            sharedAccountRepository,
            channelAccessGuard,
            teamPermissionService,
            credentialEncryptionService,
        )

    private val service =
        DeleteSharedAccountServiceImpl(
            sharedAccountRepository,
            channelAccessGuard,
            sharedAccountAccessGuard,
            lookupSupport,
        )

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
    fun `계정 등록자가 삭제하면 해당 공유 계정을 삭제함`() {
        val ch = accountShareChannel(createdBy = 1L)
        val acc = account(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc

        service.deleteAccount(1L, 1L, 10L)

        verify { sharedAccountRepository.delete(acc) }
    }

    @Test
    fun `deleteAccount는 권한 없는 사용자이면 FORBIDDEN`() {
        val ch = accountShareChannel(createdBy = 99L)
        val acc = account(createdBy = 88L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { teamPermissionService.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows<ExpectedException> { service.deleteAccount(1L, 1L, 10L) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteAccount는 계정이 없으면 NOT_FOUND`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { sharedAccountRepository.findByIdAndChannelId(99L, 1L) } returns null

        val ex = assertThrows<ExpectedException> { service.deleteAccount(1L, 1L, 99L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}

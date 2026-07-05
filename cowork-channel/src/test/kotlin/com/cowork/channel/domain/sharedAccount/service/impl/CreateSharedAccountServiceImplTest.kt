package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.presentation.data.request.CreateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class CreateSharedAccountServiceImplTest {

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

    private val service = CreateSharedAccountServiceImpl(
        sharedAccountRepository,
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

    @Test
    fun `createAccount는 CUSTOM provider에 providerLabel이 없으면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit

        val ex = assertThrows<ExpectedException> {
            service.createAccount(
                1L,
                1L,
                CreateSharedAccountRequest(
                    provider = AccountProvider.CUSTOM,
                    providerLabel = null,
                    accountIdentifier = null,
                    credential = null,
                ),
            )
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `createAccount는 credential을 암호화하여 저장함`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { credentialEncryptionService.encrypt("plainPwd") } returns "encryptedPwd"
        every { credentialEncryptionService.decrypt("encryptedPwd") } returns "plainPwd"
        every { credentialEncryptionService.mask("plainPwd") } returns "••••wd"

        val saved = slot<SharedAccount>()
        every { sharedAccountRepository.save(capture(saved)) } answers { saved.captured }

        service.createAccount(
            1L,
            1L,
            CreateSharedAccountRequest(
                provider = AccountProvider.GITHUB,
                providerLabel = null,
                accountIdentifier = "ghuser",
                credential = "plainPwd",
            ),
        )

        assertEquals("encryptedPwd", saved.captured.credential)
        assertEquals(false, saved.captured.connectedViaOAuth)
        assertEquals(1L, saved.captured.createdBy)
    }

    @Test
    fun `createAccount는 credential이 null이면 암호화를 수행하지 않음`() {
        every { channelRepository.findById(1L) } returns Optional.of(accountShareChannel())
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit

        val saved = slot<SharedAccount>()
        every { sharedAccountRepository.save(capture(saved)) } answers { saved.captured }

        service.createAccount(
            1L,
            1L,
            CreateSharedAccountRequest(
                provider = AccountProvider.GITHUB,
                providerLabel = null,
                accountIdentifier = "ghuser",
                credential = null,
            ),
        )

        assertNull(saved.captured.credential)
        verify(exactly = 0) { credentialEncryptionService.encrypt(any()) }
    }
}

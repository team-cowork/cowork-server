package com.cowork.channel.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.service.ChannelService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountCredentialCopy
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.presentation.data.request.CreateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.request.UpdateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.repository.AccountCredentialCopyRepository
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountService
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

class SharedAccountServiceTest {

    private val sharedAccountRepository = mockk<SharedAccountRepository>(relaxed = true)
    private val accountCredentialCopyRepository = mockk<AccountCredentialCopyRepository>(relaxed = true)
    private val channelService = mockk<ChannelService> {
        every { requireTeamChannel(any()) } answers { firstArg<Channel>().teamId!! }
    }
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val credentialEncryptionService = mockk<CredentialEncryptionService>()

    private val service = SharedAccountService(
        sharedAccountRepository,
        accountCredentialCopyRepository,
        channelService,
        teamPermissionService,
        credentialEncryptionService,
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

    // ── listAccounts ───────────────────────────────────────────────────────────

    @Test
    fun `listAccounts는 ACCOUNT_SHARE 채널이 아니면 BAD_REQUEST`() {
        every { channelService.findChannelOrThrow(1L) } returns textChannel()

        val ex = assertThrows<ExpectedException> { service.listAccounts(1L, 1L) }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `listAccounts는 팀 비멤버이면 FORBIDDEN`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows<ExpectedException> { service.listAccounts(7L, 1L) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `listAccounts는 credential을 ••••로 마스킹하여 반환함`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findAllByChannelIdOrderByCreatedAtAscIdAsc(1L) } returns
            listOf(account(credential = "encrypted"))

        val result = service.listAccounts(1L, 1L)

        assertEquals(1, result.size)
        assertEquals("••••", result[0].maskedCredential)
    }

    @Test
    fun `listAccounts는 credential이 null이면 maskedCredential도 null임`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findAllByChannelIdOrderByCreatedAtAscIdAsc(1L) } returns
            listOf(account(credential = null))

        val result = service.listAccounts(1L, 1L)

        assertNull(result[0].maskedCredential)
    }

    // ── getAccount ─────────────────────────────────────────────────────────────

    @Test
    fun `getAccount는 계정이 없으면 NOT_FOUND`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(99L, 1L) } returns null

        val ex = assertThrows<ExpectedException> { service.getAccount(1L, 1L, 99L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `getAccount는 credential을 복호화 후 마스킹하여 반환함`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns account(credential = "encrypted")
        every { credentialEncryptionService.decrypt("encrypted") } returns "plainPassword"
        every { credentialEncryptionService.mask("plainPassword") } returns "••••word"

        val result = service.getAccount(1L, 1L, 10L)

        assertEquals("••••word", result.maskedCredential)
    }

    // ── createAccount ──────────────────────────────────────────────────────────

    @Test
    fun `createAccount는 CUSTOM provider에 providerLabel이 없으면 BAD_REQUEST`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
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
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
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
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
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

    // ── updateAccount ──────────────────────────────────────────────────────────

    @Test
    fun `updateAccount는 계정 등록자가 수정 가능함`() {
        val ch = accountShareChannel(createdBy = 99L)
        val acc = account(createdBy = 1L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { credentialEncryptionService.decrypt(any()) } returns "plain"
        every { credentialEncryptionService.mask(any()) } returns "••••in"

        val result = service.updateAccount(
            1L,
            1L,
            10L,
            UpdateSharedAccountRequest(accountIdentifier = "newUser", credential = null, providerLabel = null),
        )

        assertEquals("newUser", result.accountIdentifier)
    }

    @Test
    fun `updateAccount는 채널 생성자가 수정 가능함`() {
        val ch = accountShareChannel(createdBy = 1L)
        val acc = account(createdBy = 99L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { credentialEncryptionService.decrypt(any()) } returns "plain"
        every { credentialEncryptionService.mask(any()) } returns "••••in"

        val result = service.updateAccount(
            1L,
            1L,
            10L,
            UpdateSharedAccountRequest(accountIdentifier = null, credential = null, providerLabel = null),
        )
        assertEquals(acc.id, result.id)
    }

    @Test
    fun `updateAccount는 팀 Admin이 수정 가능함`() {
        val ch = accountShareChannel(createdBy = 99L)
        val acc = account(createdBy = 88L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { teamPermissionService.isTeamOwnerOrAdmin(100L, 1L) } returns true
        every { credentialEncryptionService.decrypt(any()) } returns "plain"
        every { credentialEncryptionService.mask(any()) } returns "••••in"

        val result = service.updateAccount(
            1L,
            1L,
            10L,
            UpdateSharedAccountRequest(accountIdentifier = null, credential = null, providerLabel = null),
        )
        assertEquals(acc.id, result.id)
    }

    @Test
    fun `updateAccount는 권한 없는 사용자이면 FORBIDDEN`() {
        val ch = accountShareChannel(createdBy = 99L)
        val acc = account(createdBy = 88L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { teamPermissionService.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows<ExpectedException> {
            service.updateAccount(
                1L,
                1L,
                10L,
                UpdateSharedAccountRequest(accountIdentifier = null, credential = null, providerLabel = null),
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateAccount는 계정이 없으면 NOT_FOUND`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { sharedAccountRepository.findByIdAndChannelId(99L, 1L) } returns null

        val ex = assertThrows<ExpectedException> {
            service.updateAccount(
                1L,
                1L,
                99L,
                UpdateSharedAccountRequest(accountIdentifier = null, credential = null, providerLabel = null),
            )
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    // ── deleteAccount ──────────────────────────────────────────────────────────

    @Test
    fun `deleteAccount 정상 흐름`() {
        val ch = accountShareChannel(createdBy = 1L)
        val acc = account(createdBy = 1L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc

        service.deleteAccount(1L, 1L, 10L)

        verify { sharedAccountRepository.delete(acc) }
    }

    @Test
    fun `deleteAccount는 권한 없는 사용자이면 FORBIDDEN`() {
        val ch = accountShareChannel(createdBy = 99L)
        val acc = account(createdBy = 88L)
        every { channelService.findChannelOrThrow(1L) } returns ch
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns acc
        every { teamPermissionService.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows<ExpectedException> { service.deleteAccount(1L, 1L, 10L) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteAccount는 계정이 없으면 NOT_FOUND`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { sharedAccountRepository.findByIdAndChannelId(99L, 1L) } returns null

        val ex = assertThrows<ExpectedException> { service.deleteAccount(1L, 1L, 99L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    // ── copyCredential ─────────────────────────────────────────────────────────

    @Test
    fun `copyCredential은 복호화된 credential을 반환하고 접근 로그를 저장함`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
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
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 1L) } returns Unit
        every { sharedAccountRepository.findByIdAndChannelId(10L, 1L) } returns account(credential = null)

        val ex = assertThrows<ExpectedException> { service.copyCredential(1L, 1L, 10L) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `copyCredential은 팀 비멤버이면 FORBIDDEN`() {
        every { channelService.findChannelOrThrow(1L) } returns accountShareChannel()
        every { teamPermissionService.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows<ExpectedException> { service.copyCredential(7L, 1L, 10L) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `copyCredential은 ACCOUNT_SHARE 채널이 아니면 BAD_REQUEST`() {
        every { channelService.findChannelOrThrow(1L) } returns textChannel()

        val ex = assertThrows<ExpectedException> { service.copyCredential(1L, 1L, 10L) }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}

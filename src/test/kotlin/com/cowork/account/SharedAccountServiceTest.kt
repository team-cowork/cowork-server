package com.cowork.account

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.CredentialCopyHistory
import com.cowork.channel.account.domain.SharedAccount
import com.cowork.channel.account.dto.UpdateSharedAccountRequest
import com.cowork.channel.account.repository.CredentialCopyHistoryRepository
import com.cowork.channel.account.repository.SharedAccountRepository
import com.cowork.channel.account.service.SharedAccountService
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.crypto.CredentialEncryptor
import com.cowork.global.crypto.CryptoProperties
import com.cowork.global.error.BusinessException
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.util.Base64

class SharedAccountServiceTest {

    private val accountRepository = mockk<SharedAccountRepository>()
    private val historyRepository = mockk<CredentialCopyHistoryRepository>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val encryptor = CredentialEncryptor(CryptoProperties(Base64.getEncoder().encodeToString(ByteArray(32))))
    private val service = SharedAccountService(accountRepository, historyRepository, channelAccessChecker, encryptor)

    private val channel = Channel(
        teamId = 1,
        projectId = null,
        name = "accounts",
        viewType = ChannelViewType.ACCOUNT_SHARE,
        description = null,
        isPrivate = false,
        position = 0,
        createdBy = 1,
        id = 20,
    )

    private fun account(createdBy: Long, oauth: Boolean = false) = SharedAccount(
        channelId = 20,
        provider = AccountProvider.GITHUB,
        providerLabel = null,
        accountIdentifier = "cowork-bot",
        encryptedCredential = encryptor.encrypt("ghp_secret"),
        connectedViaOAuth = oauth,
        createdBy = createdBy,
        id = 30,
    )

    private fun allowRead(userId: Long) {
        every { channelAccessChecker.requireReadable(20, userId) } returns channel
        every { channelAccessChecker.requireViewType(channel, ChannelViewType.ACCOUNT_SHARE) } just runs
    }

    @Test
    fun `자격 증명을 복사하면 복호화한 값을 반환하고 이력을 남긴다`() {
        allowRead(5)
        every { accountRepository.findByIdAndChannelId(30, 20) } returns account(createdBy = 1)
        val history = slot<CredentialCopyHistory>()
        every { historyRepository.save(capture(history)) } answers { history.captured }

        val response = service.copyCredential(20, 30, 5)

        assertEquals("ghp_secret", response.credential)
        assertEquals(5, history.captured.userId)
        assertEquals(30, history.captured.accountId)
    }

    @Test
    fun `등록자나 채널 관리자가 아니면 계정을 수정할 수 없다`() {
        allowRead(5)
        every { accountRepository.findByIdAndChannelId(30, 20) } returns account(createdBy = 1)
        every { channelAccessChecker.canManage(channel, 5) } returns false

        val exception = assertThrows<BusinessException> {
            service.update(20, 30, 5, UpdateSharedAccountRequest(accountIdentifier = "new"))
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `OAuth로 연결한 계정의 자격 증명은 직접 바꿀 수 없다`() {
        allowRead(1)
        every { accountRepository.findByIdAndChannelId(30, 20) } returns account(createdBy = 1, oauth = true)

        val exception = assertThrows<BusinessException> {
            service.update(20, 30, 1, UpdateSharedAccountRequest(credential = "manual"))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}

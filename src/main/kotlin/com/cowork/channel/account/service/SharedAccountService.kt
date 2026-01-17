package com.cowork.channel.account.service

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.CredentialCopyHistory
import com.cowork.channel.account.domain.SharedAccount
import com.cowork.channel.account.dto.CreateSharedAccountRequest
import com.cowork.channel.account.dto.CredentialCopyResponse
import com.cowork.channel.account.dto.CredentialResponse
import com.cowork.channel.account.dto.SharedAccountDetailResponse
import com.cowork.channel.account.dto.SharedAccountResponse
import com.cowork.channel.account.dto.UpdateSharedAccountRequest
import com.cowork.channel.account.repository.CredentialCopyHistoryRepository
import com.cowork.channel.account.repository.SharedAccountRepository
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.crypto.CredentialEncryptor
import com.cowork.global.error.BusinessException
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SharedAccountService(
    private val sharedAccountRepository: SharedAccountRepository,
    private val credentialCopyHistoryRepository: CredentialCopyHistoryRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val credentialEncryptor: CredentialEncryptor,
) {

    @Transactional
    fun getAccounts(channelId: Long, userId: Long): List<SharedAccountResponse> {
        requireAccountChannel(channelId, userId)
        return sharedAccountRepository.findAllByChannelIdOrderByIdAsc(channelId).map(SharedAccountResponse::from)
    }

    @Transactional
    fun getAccount(channelId: Long, accountId: Long, userId: Long): SharedAccountDetailResponse {
        requireAccountChannel(channelId, userId)
        val account = findAccount(channelId, accountId)
        val copies = credentialCopyHistoryRepository.findTop20ByAccountIdOrderByIdDesc(account.id)
        return SharedAccountDetailResponse(SharedAccountResponse.from(account), copies.map(CredentialCopyResponse::from))
    }

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateSharedAccountRequest): SharedAccountResponse {
        requireAccountChannel(channelId, userId)
        if (request.provider == AccountProvider.CUSTOM && request.providerLabel.isNullOrBlank()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "직접 입력한 서비스는 이름이 필요합니다.")
        }
        val account = sharedAccountRepository.save(
            SharedAccount(
                channelId = channelId,
                provider = request.provider,
                providerLabel = request.providerLabel?.ifBlank { null },
                accountIdentifier = request.accountIdentifier?.ifBlank { null },
                encryptedCredential = request.credential?.takeIf { it.isNotEmpty() }?.let(credentialEncryptor::encrypt),
                connectedViaOAuth = false,
                createdBy = userId,
            ),
        )
        return SharedAccountResponse.from(account)
    }

    fun requireAccountChannel(channelId: Long, userId: Long): Channel {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.ACCOUNT_SHARE)
        return channel
    }

    private fun findAccount(channelId: Long, accountId: Long): SharedAccount =
        sharedAccountRepository.findByIdAndChannelId(accountId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "공유 계정을 찾을 수 없습니다.")
}

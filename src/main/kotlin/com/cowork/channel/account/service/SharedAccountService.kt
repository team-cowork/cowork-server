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

    fun requireAccountChannel(channelId: Long, userId: Long): Channel {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.ACCOUNT_SHARE)
        return channel
    }
}

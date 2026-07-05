package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.DeleteSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteSharedAccountServiceImpl(
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val lookupSupport: SharedAccountLookupSupport,
) : DeleteSharedAccountService {

    override fun deleteAccount(userId: Long, channelId: Long, accountId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        val account = lookupSupport.findAccountOrThrow(accountId, channelId)
        lookupSupport.requireAccountEditor(account, channel, userId)
        sharedAccountRepository.delete(account)
    }
}

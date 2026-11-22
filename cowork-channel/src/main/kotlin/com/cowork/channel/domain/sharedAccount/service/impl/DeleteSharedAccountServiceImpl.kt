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
    private val sharedAccountLookupSupport: SharedAccountLookupSupport,
) : DeleteSharedAccountService {

    override fun deleteAccount(userId: Long, channelId: Long, accountId: Long) {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        val account = sharedAccountLookupSupport.findAccountOrThrow(accountId, channelId)
        sharedAccountLookupSupport.requireAccountEditor(account, channel, userId)
        channelAccessGuard.requireNotArchived(channel)
        sharedAccountRepository.delete(account)
    }
}

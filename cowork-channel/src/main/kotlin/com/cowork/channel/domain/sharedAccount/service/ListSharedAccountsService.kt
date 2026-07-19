package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse

interface ListSharedAccountsService {
    fun listAccounts(userId: Long, channelId: Long): List<SharedAccountResponse>
}

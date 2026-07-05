package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse

interface GetSharedAccountService {
    fun getAccount(userId: Long, channelId: Long, accountId: Long): SharedAccountResponse
}

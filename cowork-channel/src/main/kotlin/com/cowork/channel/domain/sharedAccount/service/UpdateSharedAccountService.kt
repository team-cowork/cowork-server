package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.presentation.data.request.UpdateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse

interface UpdateSharedAccountService {
    fun updateAccount(userId: Long, channelId: Long, accountId: Long, request: UpdateSharedAccountRequest): SharedAccountResponse
}

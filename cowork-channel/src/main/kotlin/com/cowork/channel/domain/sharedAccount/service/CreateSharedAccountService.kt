package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.presentation.data.request.CreateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse

interface CreateSharedAccountService {
    fun createAccount(userId: Long, channelId: Long, request: CreateSharedAccountRequest): SharedAccountResponse
}

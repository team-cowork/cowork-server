package com.cowork.channel.domain.sharedAccount.service

interface DeleteSharedAccountService {
    fun deleteAccount(userId: Long, channelId: Long, accountId: Long)
}

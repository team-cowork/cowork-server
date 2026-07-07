package com.cowork.channel.domain.sharedAccount.service

interface CopySharedAccountCredentialService {
    fun copyCredential(userId: Long, channelId: Long, accountId: Long): String
}

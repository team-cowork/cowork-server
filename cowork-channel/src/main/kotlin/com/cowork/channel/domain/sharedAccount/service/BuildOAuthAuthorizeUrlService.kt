package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider

interface BuildOAuthAuthorizeUrlService {
    fun buildAuthorizeUrl(channelId: Long, userId: Long, provider: AccountProvider): String
}

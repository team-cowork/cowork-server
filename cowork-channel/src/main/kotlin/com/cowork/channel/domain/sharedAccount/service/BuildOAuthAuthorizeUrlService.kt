package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.support.OAuthAuthorizeRequest

interface BuildOAuthAuthorizeUrlService {
    fun buildAuthorizeUrl(
        channelId: Long,
        userId: Long,
        provider: AccountProvider,
        returnOrigin: String? = null,
    ): OAuthAuthorizeRequest
}

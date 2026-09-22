package com.cowork.channel.domain.sharedAccount.service.support

data class OAuthState(val channelId: Long, val userId: Long, val returnOrigin: String)

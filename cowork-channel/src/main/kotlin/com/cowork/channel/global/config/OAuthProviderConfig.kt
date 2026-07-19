package com.cowork.channel.global.config

data class OAuthProviderConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val tokenUrl: String = "",
    val userinfoUrl: String = "",
    val scope: String = "",
)

package com.cowork.channel.config

data class OAuthProviderConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val tokenUrl: String = "",
    val userinfoUrl: String = "",
    val scope: String = "",
)

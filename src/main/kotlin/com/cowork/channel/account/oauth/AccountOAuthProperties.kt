package com.cowork.channel.account.oauth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cowork.account-oauth")
data class AccountOAuthProperties(
    val clientRedirectUrl: String,
    val callbackBaseUrl: String,
    val providers: Map<String, Provider> = emptyMap(),
) {
    data class Provider(
        val clientId: String,
        val clientSecret: String,
        val authorizeUrl: String,
        val tokenUrl: String,
        val userInfoUrl: String,
        val scope: String = "",
        val identifierField: String = "email",
    )

    fun provider(name: String): Provider? = providers[name.lowercase()]
}

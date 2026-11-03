package com.cowork.channel.global.config

data class OAuthProperties(
    val callbackBaseUrl: String,
    val allowedReturnOrigins: List<String>,
    val stateSecret: String,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val github: OAuthProviderConfig,
    val notion: OAuthProviderConfig,
    val jira: OAuthProviderConfig,
    val google: OAuthProviderConfig,
    val facebook: OAuthProviderConfig,
) {
    fun callbackUrl(providerName: String): String =
        "${callbackBaseUrl.trimEnd('/')}/api/channel/channels/oauth/callback/${providerName.lowercase()}"
}

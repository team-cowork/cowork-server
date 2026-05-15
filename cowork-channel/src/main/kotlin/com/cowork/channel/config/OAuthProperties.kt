package com.cowork.channel.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(OAuthProperties::class)
class OAuthPropertiesConfig

@ConfigurationProperties(prefix = "account-share.oauth")
data class OAuthProperties(
    val callbackBaseUrl: String = "http://localhost:8080",
    val clientRedirectUrl: String = "http://localhost:3000",
    val stateSecret: String = "",
    val github: OAuthProviderConfig = OAuthProviderConfig(),
    val notion: OAuthProviderConfig = OAuthProviderConfig(),
    val jira: OAuthProviderConfig = OAuthProviderConfig(),
    val google: OAuthProviderConfig = OAuthProviderConfig(),
    val facebook: OAuthProviderConfig = OAuthProviderConfig(),
)

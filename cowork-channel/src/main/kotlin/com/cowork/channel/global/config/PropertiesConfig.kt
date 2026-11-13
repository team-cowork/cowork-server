package com.cowork.channel.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.getRequiredProperty
import java.net.URL

@Configuration
class PropertiesConfig {
    @Bean
    fun oAuthProperties(environment: Environment): OAuthProperties = OAuthProperties(
        callbackBaseUrl = environment.getRequiredProperty("account-share.oauth.callback-base-url"),
        allowedReturnOrigins = environment.getRequiredProperty<Array<String>>(
            "account-share.oauth.allowed-return-origins",
        ).toList(),
        stateSecret = environment.getRequiredProperty("account-share.oauth.state-secret"),
        connectTimeoutMs = environment.getRequiredProperty<Long>("account-share.oauth.connect-timeout-ms"),
        readTimeoutMs = environment.getRequiredProperty<Long>("account-share.oauth.read-timeout-ms"),
        github = environment.oAuthProvider("github"),
        notion = environment.oAuthProvider("notion"),
        jira = environment.oAuthProvider("jira"),
        google = environment.oAuthProvider("google"),
        facebook = environment.oAuthProvider("facebook"),
    )

    private fun Environment.oAuthProvider(name: String): OAuthProviderConfig = OAuthProviderConfig(
        clientId = getRequiredProperty("account-share.oauth.$name.client-id"),
        clientSecret = getRequiredProperty("account-share.oauth.$name.client-secret"),
        tokenUrl = getRequiredProperty<URL>("account-share.oauth.$name.token-url").toExternalForm(),
        userinfoUrl = getRequiredProperty<URL>("account-share.oauth.$name.userinfo-url").toExternalForm(),
        scope = getRequiredProperty("account-share.oauth.$name.scope"),
    )
}

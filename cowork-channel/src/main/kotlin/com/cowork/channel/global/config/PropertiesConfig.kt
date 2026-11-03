package com.cowork.channel.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URL

@Configuration
class PropertiesConfig {
    @Bean
    fun oAuthProperties(environment: Environment): OAuthProperties = OAuthProperties(
        callbackBaseUrl = environment.getRequiredProperty("account-share.oauth.callback-base-url"),
        allowedReturnOrigins = environment.getRequiredProperty(
            "account-share.oauth.allowed-return-origins",
            Array<String>::class.java,
        ).toList(),
        stateSecret = environment.getRequiredProperty("account-share.oauth.state-secret"),
        connectTimeoutMs = environment.getRequiredProperty("account-share.oauth.connect-timeout-ms", Long::class.java),
        readTimeoutMs = environment.getRequiredProperty("account-share.oauth.read-timeout-ms", Long::class.java),
        github = environment.oAuthProvider("github"),
        notion = environment.oAuthProvider("notion"),
        jira = environment.oAuthProvider("jira"),
        google = environment.oAuthProvider("google"),
        facebook = environment.oAuthProvider("facebook"),
    )

    private fun Environment.oAuthProvider(name: String): OAuthProviderConfig = OAuthProviderConfig(
        clientId = getRequiredProperty("account-share.oauth.$name.client-id"),
        clientSecret = getRequiredProperty("account-share.oauth.$name.client-secret"),
        tokenUrl = getRequiredProperty("account-share.oauth.$name.token-url", URL::class.java).toExternalForm(),
        userinfoUrl = getRequiredProperty("account-share.oauth.$name.userinfo-url", URL::class.java).toExternalForm(),
        scope = getRequiredProperty("account-share.oauth.$name.scope"),
    )
}

package com.cowork.channel.account.oauth

import com.cowork.global.error.BusinessException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

@Component
class OAuthProviderClient(
    restClientBuilder: RestClient.Builder,
) {
    private val restClient = restClientBuilder.build()

    fun exchangeCode(provider: AccountOAuthProperties.Provider, code: String, redirectUri: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", redirectUri)
            add("client_id", provider.clientId)
            add("client_secret", provider.clientSecret)
        }
        val response = try {
            restClient.post()
                .uri(provider.tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body<JsonNode>()
        } catch (e: RestClientException) {
            null
        }
        return response?.get("access_token")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BusinessException(HttpStatus.BAD_GATEWAY, "외부 서비스 인증에 실패했습니다.")
    }

    fun fetchIdentifier(provider: AccountOAuthProperties.Provider, accessToken: String): String? {
        val userInfo = try {
            restClient.get()
                .uri(provider.userInfoUrl)
                .headers { it.setBearerAuth(accessToken) }
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<JsonNode>()
        } catch (e: RestClientException) {
            null
        } ?: return null
        return provider.identifierField.split('.')
            .fold(userInfo as JsonNode?) { node, field -> node?.get(field) }
            ?.takeUnless { it.isNull }
            ?.asText()
    }
}

package com.cowork.auth.client

import com.cowork.global.error.BusinessException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

@Component
class DataGsmClient(
    private val properties: DataGsmProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(TIMEOUT_MILLIS)
                setReadTimeout(TIMEOUT_MILLIS)
            },
        )
        .build()

    fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): String {
        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to properties.clientId,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        )
        val response = try {
            restClient.post()
                .uri(properties.tokenUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body<TokenResponse>()
        } catch (e: RestClientException) {
            log.warn("Fail to exchange DataGSM authorization code", e)
            null
        }
        return response?.accessToken
            ?: throw BusinessException(HttpStatus.UNAUTHORIZED, "DataGSM 인증에 실패했습니다.")
    }

    fun fetchUserInfo(accessToken: String): DataGsmUserInfo = try {
        restClient.get()
            .uri(properties.userInfoUrl)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .body<DataGsmUserInfo>()
    } catch (e: RestClientException) {
        log.warn("Fail to fetch DataGSM user info", e)
        null
    } ?: throw BusinessException(HttpStatus.UNAUTHORIZED, "DataGSM 사용자 정보를 가져오지 못했습니다.")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @JsonProperty("access_token")
        val accessToken: String?,
    )

    companion object {
        private const val TIMEOUT_MILLIS = 5_000
    }
}

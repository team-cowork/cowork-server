package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.global.config.OAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class OAuthStateSupportTest {

    @Test
    fun `설정하지 않은 선택형 OAuth provider는 사용 시점에 SERVICE_UNAVAILABLE을 반환한다`() {
        val support = OAuthStateSupport(
            OAuthProperties(
                callbackBaseUrl = "https://api.example.com",
                clientRedirectUrl = "https://web.example.com",
                stateSecret = "test-state-secret",
            ),
            jacksonObjectMapper(),
        )

        val exception = assertThrows<ExpectedException> {
            support.providerConfigOf(AccountProvider.GITHUB)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.statusCode)
    }
}

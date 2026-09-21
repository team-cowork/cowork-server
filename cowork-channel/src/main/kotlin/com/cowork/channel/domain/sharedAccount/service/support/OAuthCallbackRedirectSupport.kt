package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.service.HandleOAuthCallbackService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import java.net.URI

@Component
class OAuthCallbackRedirectSupport(
    private val oAuthStateSupport: OAuthStateSupport,
    private val handleOAuthCallbackService: HandleOAuthCallbackService,
) {
    private val log = LoggerFactory.getLogger(OAuthCallbackRedirectSupport::class.java)

    fun resolveRedirect(providerName: String, code: String?, state: String, error: String?): URI {
        val provider = runCatching { AccountProvider.valueOf(providerName.uppercase()) }.getOrElse {
            throw ExpectedException("지원하지 않는 OAuth provider입니다.", HttpStatus.BAD_REQUEST)
        }
        // 실패 응답도 서명·만료·허용 목록을 검증한 Origin에만 보낸다.
        val returnOrigin = oAuthStateSupport.verifyState(state, provider).returnOrigin
        val errorUrl = URI.create("$returnOrigin/error?message=oauth_failed")
        if (error != null || code.isNullOrBlank()) return errorUrl

        return try {
            // 서비스의 트랜잭션이 완료된 후 리다이렉트 주소를 결정한다.
            val account = handleOAuthCallbackService.handleCallback(providerName, code, state)
            URI.create("$returnOrigin/channels/${account.channelId}?newAccountId=${account.id}")
        } catch (ex: Exception) {
            log.warn("Fail to complete account OAuth callback")
            errorUrl
        }
    }
}

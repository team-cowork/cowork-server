package com.cowork.channel.account.controller

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.service.OAuthAccountService
import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@Tag(name = "계정 공유")
@RestController
class OAuthAccountController(
    private val oAuthAccountService: OAuthAccountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(summary = "외부 서비스 OAuth 연결 시작 (authorize URL 반환)")
    @GetMapping("/channels/{channelId}/accounts/oauth/authorize/{provider}")
    fun authorize(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable provider: String,
    ): Map<String, String> {
        val accountProvider = AccountProvider.entries.firstOrNull { it.name.equals(provider, ignoreCase = true) }
            ?: throw BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 서비스입니다.")
        return mapOf("redirectUrl" to oAuthAccountService.buildAuthorizeUrl(channelId, user.id, accountProvider))
    }

    @Operation(hidden = true)
    @GetMapping("/channels/oauth/callback/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestParam code: String,
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val baseUrl = oAuthAccountService.clientRedirectUrl()
        val location = try {
            val account = oAuthAccountService.handleCallback(provider, code, state)
            "$baseUrl/channels/${account.channelId}?newAccountId=${account.id}"
        } catch (e: BusinessException) {
            log.warn("Fail to connect OAuth account: {}", e.message)
            "$baseUrl/error?message=oauth_failed"
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()
    }
}

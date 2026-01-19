package com.cowork.channel.account.controller

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.service.OAuthAccountService
import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class OAuthAccountController(
    private val oAuthAccountService: OAuthAccountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
}

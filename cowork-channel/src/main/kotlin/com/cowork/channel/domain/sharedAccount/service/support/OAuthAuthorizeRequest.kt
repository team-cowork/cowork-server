package com.cowork.channel.domain.sharedAccount.service.support

/** provider 인증 URL과, 흐름을 시작한 브라우저 쿠키에 담을 state nonce. */
data class OAuthAuthorizeRequest(val redirectUrl: String, val nonce: String)

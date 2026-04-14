package com.cowork.gateway.security

import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class JwtReactiveAuthenticationManager : ReactiveAuthenticationManager {

    override fun authenticate(authentication: Authentication): Mono<Authentication> {
        // 토큰 파싱·검증은 Converter에서 완료됨.
        // 여기서는 이미 유효한 Authentication 객체를 그대로 반환.
        return Mono.just(authentication)
    }
}

package com.cowork.gateway.security

import com.cowork.gateway.filter.WsOriginFilter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties::class, WsProperties::class)
class SecurityConfig(
    private val jwtConverter: JwtServerAuthenticationConverter,
    private val jwtAuthManager: JwtReactiveAuthenticationManager,
    private val jwtAuthenticationSupport: JwtAuthenticationSupport,
    private val wsProperties: WsProperties,
) {

    /**
     * `/ws` 하위 전체 전용 보안 체인. 브라우저의 WebSocket 핸드셰이크는 커스텀 `Authorization`
     * 헤더를 실을 수 없어 쿠키 기반 인증(`WsJwtServerAuthenticationConverter`)과
     * Origin 검사(`WsOriginFilter`)가 이 경로에만 필요하다. 별도 체인으로 분리해
     * 기본 체인과 그 컨버터가 이 예외를 몰라도 되게 한다.
     * 모듈별 웹소켓은 `/ws/{module}`(예: `/ws/chat`) 하위로 두어 이 체인을 그대로 재사용한다.
     */
    @Bean
    @Order(1)
    fun wsSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        val jwtFilter = AuthenticationWebFilter(jwtAuthManager).apply {
            setServerAuthenticationConverter(WsJwtServerAuthenticationConverter(jwtAuthenticationSupport))
            setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        }

        return http
            .securityMatcher(PathPatternParserServerWebExchangeMatcher("/ws/**"))
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange { it.anyExchange().permitAll() }
            .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .addFilterAt(WsOriginFilter(wsProperties), SecurityWebFiltersOrder.FIRST)
            .build()
    }

    @Bean
    @Order(2)
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        val jwtFilter = AuthenticationWebFilter(jwtAuthManager).apply {
            setServerAuthenticationConverter(jwtConverter)
            setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        }

        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { exchange, _ ->
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.setComplete()
                }
            }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .pathMatchers(
                        HttpMethod.POST,
                        "/api/authorization/auth/token",
                        "/api/authorization/auth/refresh",
                    ).permitAll()
                    .pathMatchers("/actuator/**").permitAll()
                    .pathMatchers("/api/health").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/chat/health", "/api/chat/chat/health/ready").permitAll()
                    .pathMatchers("/fallback").permitAll()
                    .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/webjars/**").permitAll()
                    .pathMatchers("/v3/api-docs/**").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/chat/asyncapi.json").permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/voice/voice/webhook").permitAll()
                    // DataGSM webhook은 Cowork JWT가 아닌 HMAC 서명으로 인증하므로 Gateway JWT 검증을 우회한다
                    .pathMatchers(HttpMethod.POST, "/api/authorization/events/datagsm").permitAll()
                    // OAuth provider가 사용자 브라우저를 리다이렉트하는 콜백이므로 Cowork JWT 없음
                    .pathMatchers(HttpMethod.GET, "/api/channel/channels/oauth/callback/**").permitAll()
                    .anyExchange().authenticated()
            }
            .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }
}

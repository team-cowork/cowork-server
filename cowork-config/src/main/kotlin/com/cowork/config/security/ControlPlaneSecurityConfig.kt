package com.cowork.config.security

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint

@Configuration
@EnableWebSecurity
class ControlPlaneSecurityConfig {
    private val log = LoggerFactory.getLogger(javaClass)
    private val policy = ControlPlaneAccessPolicy()

    @Bean
    fun controlPlaneAccounts(environment: Environment): ControlPlaneAccounts = ControlPlaneAccounts(environment)

    @Bean
    fun userDetailsService(accounts: ControlPlaneAccounts) = accounts.userDetailsService()

    @Bean
    fun passwordEncoder(): PasswordEncoder = ControlPlanePasswordEncoder()

    @Bean
    fun controlPlaneSecurity(http: HttpSecurity, accounts: ControlPlaneAccounts): SecurityFilterChain {
        val challenge = BasicAuthenticationEntryPoint().apply { setRealmName("cowork-control-plane") }
        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .csrf { it.ignoringRequestMatchers("/eureka/**") }
            .authorizeHttpRequests { rules ->
                rules.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                rules.anyRequest().access { authentication, context ->
                    val account = accounts.accounts[authentication.get().name]
                    AuthorizationDecision(
                        account != null && policy.allows(
                            account.application,
                            account.profile,
                            context.request.method,
                            context.request.servletPath + (context.request.pathInfo ?: ""),
                        ),
                    )
                }
            }
            .httpBasic { basic ->
                basic.authenticationEntryPoint { request, response, exception ->
                    log.warn("Reject control-plane authentication: remote={}", request.remoteAddr)
                    challenge.commence(request, response, exception)
                }
            }
            .exceptionHandling { exceptions ->
                exceptions.accessDeniedHandler { request, response, _ ->
                    log.warn("Reject control-plane authorization: remote={}", request.remoteAddr)
                    response.status = 403
                }
            }
            .addFilterAfter(EurekaRegistrationGuard(accounts, policy), AuthorizationFilter::class.java)
        return http.build()
    }
}

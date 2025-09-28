package com.cowork.support

import com.cowork.global.error.GlobalExceptionHandler
import com.cowork.global.security.AuthUser
import com.cowork.user.domain.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders

abstract class ControllerTestSupport {

    protected val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    protected val loginUser = AuthUser(1, UserRole.MEMBER)

    protected fun mockMvc(vararg controllers: Any): MockMvc =
        MockMvcBuilders.standaloneSetup(*controllers)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    protected fun json(value: Any): String = objectMapper.writeValueAsString(value)

    @BeforeEach
    fun login() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(loginUser, null, emptyList())
    }

    @AfterEach
    fun logout() {
        SecurityContextHolder.clearContext()
    }
}

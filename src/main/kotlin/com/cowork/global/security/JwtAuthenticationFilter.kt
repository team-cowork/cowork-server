package com.cowork.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        resolveToken(request)
            ?.let(jwtTokenProvider::parseAccessToken)
            ?.let { user ->
                val authentication = UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.removePrefix(BEARER_PREFIX).trim()
        }
        return null
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}

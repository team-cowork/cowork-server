package com.cowork.global.security

import com.cowork.user.domain.UserRole
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * MSA 전환 기간에 API 게이트웨이가 검증한 사용자 정보를 헤더로 받는다.
 * cowork.auth.trust-gateway-headers=true일 때만 등록하며, 외부에서 직접 접근할 수 없는 환경에서만 켠다.
 */
class GatewayHeaderAuthenticationFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            request.getHeader(USER_ID_HEADER)?.toLongOrNull()?.let { userId ->
                val role = request.getHeader(USER_ROLE_HEADER)
                    ?.let { header -> UserRole.entries.firstOrNull { it.name == header } }
                    ?: UserRole.MEMBER
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                    AuthUser(userId, role),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${role.name}")),
                )
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val USER_ID_HEADER = "X-User-Id"
        const val USER_ROLE_HEADER = "X-User-Role"
    }
}

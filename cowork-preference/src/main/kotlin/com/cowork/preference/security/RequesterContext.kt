package com.cowork.preference.security

import io.vertx.ext.web.RoutingContext

/**
 * cowork-gateway가 인증 후 전달하는 요청자 식별 정보.
 * `X-User-Id`는 양수 Long으로 파싱되어야 하고 `X-User-Role`은 `ADMIN` 또는 `MEMBER`만 허용한다.
 */
data class RequesterContext(val userId: Long, val role: String) {
    val isGlobalAdmin: Boolean get() = role == GLOBAL_ADMIN_ROLE

    companion object {
        private const val GLOBAL_ADMIN_ROLE = "ADMIN"
        private val VALID_ROLES = setOf("ADMIN", "MEMBER")

        /** 헤더가 없거나 형식이 잘못되면 null을 반환하며, 호출부는 이를 400 Bad Request로 매핑해야 한다. */
        fun from(ctx: RoutingContext): RequesterContext? {
            val rawUserId: String? = ctx.request().getHeader("X-User-Id")
            val userId = rawUserId?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val rawRole: String? = ctx.request().getHeader("X-User-Role")
            val role = rawRole?.takeIf { it in VALID_ROLES } ?: return null
            return RequesterContext(userId, role)
        }
    }
}

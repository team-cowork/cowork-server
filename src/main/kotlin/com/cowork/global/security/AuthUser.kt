package com.cowork.global.security

import com.cowork.user.domain.UserRole

data class AuthUser(
    val id: Long,
    val role: UserRole,
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN
}

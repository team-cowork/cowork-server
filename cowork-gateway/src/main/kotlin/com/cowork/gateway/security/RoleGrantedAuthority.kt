package com.cowork.gateway.security

import org.springframework.security.core.GrantedAuthority

class RoleGrantedAuthority(private val role: String) : GrantedAuthority {

    override fun getAuthority(): String =
        if (role.startsWith("ROLE_")) role else "ROLE_$role"
}
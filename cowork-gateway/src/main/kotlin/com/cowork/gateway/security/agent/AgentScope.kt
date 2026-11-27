package com.cowork.gateway.security.agent

import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Agent JWT scope와 그 scope로 호출할 수 있는 요청 목록.
 * 값은 cowork-authorization의 `domain.AgentScopes`와 같아야 한다.
 * 목록에 없는 요청은 [AgentScopeWebFilter]가 거부하므로, 경로는 대소문자와 끝 슬래시까지 정확히 일치해야 통과한다.
 */
enum class AgentScope(val value: String, vararg requests: Pair<HttpMethod, String>) {
    WORKSPACE_READ(
        "workspace:read",
        HttpMethod.GET to "/api/team/teams",
        HttpMethod.GET to "/api/channel/teams/*/channels",
        HttpMethod.GET to "/api/channel/channels/*/threads",
    ),
    CHAT_READ("chat:read", HttpMethod.GET to "/api/chat/chat/channels/*/messages"),
    CHAT_WRITE("chat:write", HttpMethod.POST to "/api/chat/chat/channels/*/messages"),
    ;

    private val requests: List<Pair<HttpMethod, PathPattern>> =
        requests.map { (method, pattern) -> method to PathPatternParser.defaultInstance.parse(pattern) }

    val authority: GrantedAuthority = SimpleGrantedAuthority("SCOPE_$value")

    fun allows(method: HttpMethod, path: PathContainer): Boolean =
        requests.any { (allowed, pattern) -> allowed == method && pattern.matches(path) }

    companion object {
        const val TOKEN_USE_CLAIM = "token_use"
        const val SCOPE_CLAIM = "scope"
        const val AGENT_TOKEN_USE = "agent"

        val AGENT_AUTHORITY: GrantedAuthority = SimpleGrantedAuthority("TOKEN_USE_$AGENT_TOKEN_USE")

        /** 비어 있거나 알 수 없는 scope가 하나라도 있으면 null을 반환한다. */
        fun parse(scope: String?): Set<AgentScope>? {
            val values = scope?.split(' ')?.takeIf { it.isNotEmpty() } ?: return null
            return values.map { value -> entries.firstOrNull { it.value == value } ?: return null }.toSet()
        }

        fun allows(authentication: Authentication, method: HttpMethod, path: PathContainer): Boolean =
            entries.any { it.authority in authentication.authorities && it.allows(method, path) }
    }
}

fun Authentication.isAgent(): Boolean = AgentScope.AGENT_AUTHORITY in authorities

package com.cowork.gateway.security.websocket

import com.cowork.gateway.security.agent.isAgent
import org.springframework.http.ResponseCookie
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class WsCookieService {
    fun createCookie(authentication: Authentication, now: Instant = Instant.now()): ResponseCookie? {
        if (!authentication.isAuthenticated || authentication.isAgent()) return null
        val token = (authentication.credentials as? String)?.takeIf { it.isNotBlank() } ?: return null
        val details = authentication.details as? Map<*, *> ?: return null
        val expiresAt = (details["expiresAt"] as? String)?.toLongOrNull() ?: return null
        val remainingSeconds = Duration.between(now, Instant.ofEpochSecond(expiresAt)).seconds
        if (remainingSeconds <= 0) return null

        return cookie(token, remainingSeconds)
    }

    fun clearCookie(): ResponseCookie = cookie("", 0)

    private fun cookie(value: String, maxAge: Long): ResponseCookie = ResponseCookie.from(COOKIE_NAME, value)
        .path("/ws")
        .httpOnly(true)
        .secure(true)
        .sameSite("None")
        .maxAge(maxAge)
        .build()

    companion object {
        const val COOKIE_NAME = "cowork_ws_token"
    }
}

package com.cowork.voice.livekit

import com.cowork.global.error.BusinessException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64

/**
 * LiveKit 웹훅의 Authorization 헤더(JWT)를 검증한다.
 * 토큰의 sha256 클레임은 요청 본문의 SHA-256 해시를 Base64로 인코딩한 값이다.
 */
@Component
class LiveKitWebhookVerifier(
    private val properties: LiveKitProperties,
) {
    private val key = Keys.hmacShaKeyFor(properties.apiSecret.toByteArray())

    fun verify(authorization: String?, body: String) {
        val token = authorization?.removePrefix("Bearer ")?.trim()
            ?: throw BusinessException(HttpStatus.UNAUTHORIZED, "웹훅 인증 정보가 없습니다.")
        val claims = try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        } catch (e: JwtException) {
            throw BusinessException(HttpStatus.UNAUTHORIZED, "웹훅 서명이 올바르지 않습니다.")
        }
        if (claims.issuer != properties.apiKey) {
            throw BusinessException(HttpStatus.UNAUTHORIZED, "웹훅 발급자가 올바르지 않습니다.")
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray()),
        )
        val actual = claims["sha256"] as? String
        if (actual == null || !MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            throw BusinessException(HttpStatus.UNAUTHORIZED, "웹훅 본문 해시가 일치하지 않습니다.")
        }
    }
}

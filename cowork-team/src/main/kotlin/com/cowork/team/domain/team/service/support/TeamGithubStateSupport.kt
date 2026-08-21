package com.cowork.team.domain.team.service.support

import com.cowork.team.global.config.TeamGithubProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class TeamGithubStateSupport(private val properties: TeamGithubProperties, private val objectMapper: ObjectMapper) {

    init {
        require(properties.stateSecret.isNotBlank()) { "team-github.state-secret must not be empty" }
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { teamId, userId, nonce, exp }
    fun buildState(teamId: Long, userId: Long): String {
        val payload = mapOf(
            "teamId" to teamId,
            "userId" to userId,
            "nonce" to UUID.randomUUID().toString(),
            "exp" to (Instant.now().epochSecond + 300),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payloadJson = objectMapper.writeValueAsString(payload)
        val payloadB64 = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signature = hmacSign(payloadB64)
        return "$payloadB64.$signature"
    }

    fun verifyState(state: String): Pair<Long, Long> {
        val parts = state.split(".")
        if (parts.size != 2) throw ExpectedException("유효하지 않은 state입니다.", HttpStatus.BAD_REQUEST)

        val (payloadB64, signature) = parts
        if (hmacSign(payloadB64) != signature) {
            throw ExpectedException("state 서명 검증에 실패했습니다.", HttpStatus.BAD_REQUEST)
        }

        val decoder = Base64.getUrlDecoder()
        val payloadJson = String(decoder.decode(payloadB64), Charsets.UTF_8)

        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>

        val exp = (payload["exp"] as? Number)?.toLong()
            ?: throw ExpectedException("state payload가 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        if (Instant.now().epochSecond > exp) {
            throw ExpectedException("state가 만료되었습니다.", HttpStatus.BAD_REQUEST)
        }

        val teamId = (payload["teamId"] as? Number)?.toLong()
            ?: throw ExpectedException("state의 teamId 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        val userId = (payload["userId"] as? Number)?.toLong()
            ?: throw ExpectedException("state의 userId 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)

        return teamId to userId
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.stateSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}

package com.cowork.project.domain.github.service

import com.cowork.project.global.config.TeamGithubProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `cowork-team`이 발급한 GitHub 설치 연동 state를 검증한다.
 *
 * `cowork-channel`의 `OAuthStateSupport.verifyState`와 동일한 HMAC-SHA256 서명 검증 방식을
 * 따르되, provider 필드 없이 `{teamId, userId, nonce, exp}` payload만 다룬다.
 * `cowork-team`과 동일한 `team-github.state-secret`(env: `TEAM_GITHUB_STATE_SECRET`) 값을
 * 공유해야만 서로 발급한 state를 검증할 수 있다.
 */
@Component
class TeamGithubStateVerifier(
    private val teamGithubProperties: TeamGithubProperties,
    private val objectMapper: ObjectMapper,
) {

    init {
        require(teamGithubProperties.stateSecret.isNotBlank()) { "team-github.state-secret must not be empty" }
    }

    // state = base64url(json_payload).base64url(hmac-sha256)
    // payload: { teamId, userId, nonce, exp }
    fun verifyState(state: String): Long {
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

        return (payload["teamId"] as? Number)?.toLong()
            ?: throw ExpectedException("state의 teamId 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(teamGithubProperties.stateSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}

package com.cowork.team.domain.team.service.support

import com.cowork.team.global.config.TeamGithubProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TeamGithubStateSupportTest {

    private val objectMapper = ObjectMapper()
    private val properties = TeamGithubProperties(stateSecret = "test-secret", appSlug = "cowork-app")
    private val support = TeamGithubStateSupport(properties, objectMapper)

    private fun sign(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun buildRawState(secret: String, teamId: Long, userId: Long, exp: Long): String {
        val payload = mapOf(
            "teamId" to teamId,
            "userId" to userId,
            "nonce" to "fixed-nonce",
            "exp" to exp,
        )
        val payloadJson = objectMapper.writeValueAsString(payload)
        val payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signature = sign(secret, payloadB64)
        return "$payloadB64.$signature"
    }

    @Test
    fun `buildState로 발급한 state를 verifyState로 검증하면 teamId와 userId를 그대로 복원한다`() {
        val teamId = 42L
        val userId = 7L

        val state = support.buildState(teamId, userId)
        val (verifiedTeamId, verifiedUserId) = support.verifyState(state)

        assertEquals(teamId, verifiedTeamId)
        assertEquals(userId, verifiedUserId)
    }

    @Test
    fun `서명이 위조된 state는 검증에 실패한다`() {
        val validState = support.buildState(1L, 2L)
        val (payloadB64, _) = validState.split(".")
        val forgedState = "$payloadB64.forged-signature"

        assertThrows(ExpectedException::class.java) { support.verifyState(forgedState) }
    }

    @Test
    fun `다른 시크릿으로 서명된 state는 검증에 실패한다`() {
        val forgedState = buildRawState(
            secret = "another-secret",
            teamId = 1L,
            userId = 2L,
            exp = Instant.now().epochSecond + 300,
        )

        assertThrows(ExpectedException::class.java) { support.verifyState(forgedState) }
    }

    @Test
    fun `만료된 state는 검증에 실패한다`() {
        val expiredState = buildRawState(
            secret = "test-secret",
            teamId = 1L,
            userId = 2L,
            exp = Instant.now().epochSecond - 10,
        )

        assertThrows(ExpectedException::class.java) { support.verifyState(expiredState) }
    }
}

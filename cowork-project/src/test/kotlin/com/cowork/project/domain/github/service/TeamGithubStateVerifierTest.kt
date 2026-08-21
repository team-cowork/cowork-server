package com.cowork.project.domain.github.service

import com.cowork.project.global.config.TeamGithubProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TeamGithubStateVerifierTest :
    DescribeSpec({

        val secret = "test-state-secret"
        val objectMapper = jacksonObjectMapper()
        val verifier = TeamGithubStateVerifier(TeamGithubProperties(stateSecret = secret), objectMapper)

        fun sign(data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
        }

        fun buildState(teamId: Long, exp: Long = Instant.now().epochSecond + 300): String {
            val payload = mapOf("teamId" to teamId, "userId" to 1L, "nonce" to "n", "exp" to exp)
            val payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8))
            return "$payloadB64.${sign(payloadB64)}"
        }

        describe("TeamGithubStateVerifier 클래스의") {
            describe("verifyState 메서드는") {
                context("유효한 state가 주어지면") {
                    it("teamId를 반환한다") {
                        val state = buildState(teamId = 42L)

                        val teamId = verifier.verifyState(state)

                        teamId shouldBe 42L
                    }
                }

                context("서명이 일치하지 않으면") {
                    it("BAD_REQUEST를 던진다") {
                        val state = buildState(teamId = 42L) + "tampered"

                        val ex = shouldThrow<ExpectedException> { verifier.verifyState(state) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("만료된 state가 주어지면") {
                    it("BAD_REQUEST를 던진다") {
                        val state = buildState(teamId = 42L, exp = Instant.now().epochSecond - 10)

                        val ex = shouldThrow<ExpectedException> { verifier.verifyState(state) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("형식이 올바르지 않으면") {
                    it("BAD_REQUEST를 던진다") {
                        val ex = shouldThrow<ExpectedException> { verifier.verifyState("invalid-state") }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }
        }
    })

package com.cowork.config.security.account

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.env.MockEnvironment

class ControlPlaneAccountsTest :
    DescribeSpec({
        val hash = "a".repeat(64)
        val accountsJson = """[{"username":"chat-prod","passwordHash":"$hash","application":"cowork-chat","profile":"prod"}]"""

        fun environment(vararg profiles: String, sslEnabled: Boolean? = null) = MockEnvironment().apply {
            setActiveProfiles(*profiles)
            setProperty("CONFIG_SERVER_ACCOUNTS_JSON", accountsJson)
            if (sslEnabled != null) setProperty("server.ssl.enabled", sslEnabled.toString())
        }

        describe("운영 Config Server의 TLS 요구") {
            it("TLS가 켜져 있으면 계정을 로드한다") {
                ControlPlaneAccounts(environment("prod", sslEnabled = true)).accounts.keys shouldBe setOf("chat-prod")
            }
            it("TLS도 private-http 프로파일도 없으면 기동을 거부한다") {
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(environment("prod")) }
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(environment("prod", sslEnabled = false)) }
            }
            it("private-http 프로파일이면 TLS 없이도 계정 인증은 유지한 채 기동한다") {
                ControlPlaneAccounts(environment("prod", "private-http")).accounts.keys shouldBe setOf("chat-prod")
            }
            it("private-http여도 계정 문서가 잘못되면 거부한다") {
                val invalid = MockEnvironment().apply {
                    setActiveProfiles("prod", "private-http")
                    setProperty("CONFIG_SERVER_ACCOUNTS_JSON", """[{"username":"x","passwordHash":"zz","application":"cowork-chat","profile":"prod"}]""")
                }
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(invalid) }
            }
        }
        describe("배포 프로파일 선택") {
            it("local과 prod를 함께 선택하면 거부한다") {
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(environment("local", "prod", sslEnabled = true)) }
            }
        }
    })

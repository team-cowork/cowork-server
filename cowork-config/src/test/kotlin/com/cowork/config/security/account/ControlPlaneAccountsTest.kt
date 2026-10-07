package com.cowork.config.security.account

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.env.MockEnvironment

class ControlPlaneAccountsTest :
    DescribeSpec({
        fun accountsJson(passwordHash: String, profile: String = "prod") =
            "[{\"username\":\"chat-prod\",\"passwordHash\":\"$passwordHash\"," +
                "\"application\":\"cowork-chat\",\"profile\":\"$profile\"}]"

        fun environment(vararg profiles: String, json: String = accountsJson("a".repeat(64))) =
            MockEnvironment().apply {
                setActiveProfiles(*profiles)
                setProperty("CONFIG_SERVER_ACCOUNTS_JSON", json)
            }

        describe("Config Server 서비스 계정 로드") {
            it("운영 프로파일에서 TLS 설정 없이도 계정을 로드한다") {
                val accounts = ControlPlaneAccounts(environment("prod"))
                accounts.accounts.keys shouldBe setOf("chat-prod")
            }
            it("계정의 비밀번호 해시 형식이 잘못되면 거부한다") {
                val invalid = environment("prod", json = accountsJson("zz"))
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(invalid) }
            }
            it("배포 프로파일과 다른 profile의 계정이 있으면 거부한다") {
                val mismatched = environment("prod", json = accountsJson("a".repeat(64), profile = "local"))
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(mismatched) }
            }
        }
        describe("배포 프로파일 선택") {
            it("local과 prod를 함께 선택하면 거부한다") {
                val both = environment("local", "prod")
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(both) }
            }
            it("배포 프로파일이 없으면 거부한다") {
                shouldThrow<IllegalArgumentException> { ControlPlaneAccounts(environment()) }
            }
        }
    })

package com.cowork.config.security.policy

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ControlPlaneAccessPolicyTest :
    DescribeSpec({
        val policy = ControlPlaneAccessPolicy()
        val application = "cowork-chat"

        describe("서비스별 설정 접근 정책") {
            it("자기 서비스의 배포 프로필과 Spring 초기 default 조회를 허용한다") {
                policy.allows(application, "prod", "GET", "/cowork-chat/prod") shouldBe true
                policy.allows(application, "prod", "GET", "/cowork-chat/default") shouldBe true
            }
            it("다른 서비스, 환경, 공통 설정과 우회 조회 형식을 거부한다") {
                listOf(
                    "/cowork-user/prod", "/application/prod", "/cowork-config/prod", "/cowork-chat/local",
                    "/cowork-chat/prod,local", "/cowork-chat,cowork-user/prod", "/cowork-chat/prod/main",
                    "/cowork-chat-prod.yml", "/cowork-chat/prod/secret.txt", "/cowork-chat/prod/",
                    "/cowork-chat/../cowork-user/prod", "/cowork-chat%2Fcowork-user/prod", "/encrypt", "/decrypt",
                ).forEach { policy.allows(application, "prod", "GET", it) shouldBe false }
                policy.allows(application, "prod", "POST", "/cowork-chat/prod") shouldBe false
            }
        }
        describe("Eureka 접근 정책") {
            it("인증된 서비스의 registry 조회와 자기 서비스 등록 수명주기를 허용한다") {
                listOf("/eureka/apps", "/eureka/apps/", "/eureka/apps/delta", "/eureka/apps/COWORK-USER")
                    .forEach { policy.allows(application, "prod", "GET", it) shouldBe true }
                policy.allows(application, "prod", "POST", "/eureka/apps/COWORK-CHAT") shouldBe true
                policy.allows(application, "prod", "PUT", "/eureka/apps/cowork-chat/node:chat:8087") shouldBe true
                policy.allows(application, "prod", "DELETE", "/eureka/apps/cowork-chat/node:chat:8087") shouldBe true
                val statusPath = "/eureka/apps/cowork-chat/node:chat:8087/status"
                policy.allows(application, "prod", "PUT", statusPath) shouldBe true
            }
            it("다른 서비스 변경과 관리 API를 거부한다") {
                listOf("POST", "PUT", "DELETE").forEach { method ->
                    listOf(
                        "/eureka/apps/cowork-user",
                        "/eureka/apps/cowork-user/node:8082",
                        "/eureka/peerreplication/batch",
                    )
                        .forEach { policy.allows(application, "prod", method, it) shouldBe false }
                }
            }
            it("등록 본문의 application과 discovery VIP 사칭을 거부한다") {
                policy.allowsRegistration(application, "COWORK-CHAT", "cowork-chat", "cowork-chat") shouldBe true
                policy.allowsRegistration(application, "COWORK-USER", "cowork-chat", "cowork-chat") shouldBe false
                policy.allowsRegistration(application, "COWORK-CHAT", "cowork-gateway", "cowork-chat") shouldBe false
                policy.allowsRegistration(application, "COWORK-CHAT", "cowork-chat", "cowork-gateway") shouldBe false
                policy.allowsRegistration(application, null, null, null) shouldBe false
            }
        }
        describe("모니터링 접근 정책") {
            it("모니터링 계정은 registry와 metrics만 조회한다") {
                policy.allows("monitoring", "prod", "GET", "/eureka/apps") shouldBe true
                policy.allows("monitoring", "prod", "GET", "/actuator/prometheus") shouldBe true
                policy.allows("monitoring", "prod", "GET", "/cowork-chat/prod") shouldBe false
                policy.allows("monitoring", "prod", "POST", "/eureka/apps/cowork-chat") shouldBe false
                policy.allows(application, "prod", "GET", "/actuator/prometheus") shouldBe false
                policy.allows(application, "prod", "GET", "/actuator/env") shouldBe false
                policy.allows("monitoring", "prod", "POST", "/actuator/busrefresh") shouldBe false
            }
        }
    })

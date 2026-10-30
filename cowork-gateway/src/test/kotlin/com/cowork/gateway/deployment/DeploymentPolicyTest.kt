package com.cowork.gateway.deployment

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.net.URI

class DeploymentPolicyTest :
    DescribeSpec({
        describe("배포 안내 대상 판정") {
            val matcher = DeploymentRouteMatcher(
                mapOf("cowork-user" to "http://127.0.0.1:18082", "cowork-chat" to "http://127.0.0.1:18087"),
            )
            it("URL 경로가 아니라 실제 라우트 목적지로 서비스를 정한다") {
                matcher.serviceFor(URI.create("http://127.0.0.1:18082/api/channel/teams")) shouldBe "cowork-user"
                matcher.serviceFor(URI.create("ws://127.0.0.1:18087/ws/chat")) shouldBe "cowork-chat"
                matcher.serviceFor(URI.create("http://127.0.0.1:19101/api/user/avatars")) shouldBe null
            }
        }
    })

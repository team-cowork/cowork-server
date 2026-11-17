package com.cowork.team.global.policy

import com.cowork.team.global.consumer.AccountRolePolicyEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

class AccountRolePolicyEventTest :
    DescribeSpec({
        it("발행된 역할의 isDefault와 추가 필드를 읽고 키를 검사한다") {
            val event = jacksonObjectMapper().readValue<AccountRolePolicyEvent>(
                """{"eventType":"ROLE","id":3,"roleId":3,"name":"creator","isDefault":true,
                "permissions":{"create_team":true},"deleted":false,"occurredAt":"2026-11-17T00:00:00Z"}""",
            )
            event.isDefault shouldBe true
            event.validKey("role:3") shouldBe true
            event.validKey("role:4") shouldBe false
            event.copy(permissions = mapOf("unknown" to true)).validKey("role:3") shouldBe false
        }

        it("할당은 양수 사용자 식별자와 역할 식별자가 키와 일치해야 한다") {
            val event = AccountRolePolicyEvent("ASSIGNMENT", 3L, 7L, deleted = false, occurredAt = Instant.EPOCH)
            event.validKey("assignment:7:3") shouldBe true
            event.copy(accountId = 0L).validKey("assignment:0:3") shouldBe false
            event.validKey("assignment:8:3") shouldBe false
        }
    })

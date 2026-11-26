package com.cowork.team.global.consumer

import com.cowork.team.global.policy.PermissionKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper

class TeamAccessStateTest :
    DescribeSpec({
        val mapper = jacksonObjectMapper()
        val keys = PermissionKeys.TEAM_SCOPED
        fun record() = mapper.createObjectNode().apply {
            put("eventType", "UPSERT")
            put("teamId", 3L)
            put("accountId", 7L)
            put("occurredAt", "2026-10-01T00:00:02.000001Z")
            put("sourceMembershipVersion", "2026-10-01T00:00:02Z")
            put("sourceAuthorizationVersion", "2026-10-01T00:00:01Z")
            putArray("baseGrants")
            putObject("permissions").apply {
                keys.forEach { put(it, false) }
                put("administrator", true)
            }
        }

        it("관리자 여부와 관계없이 요청한 키의 판정을 보존한다") {
            val event = record()
            shouldThrow<IllegalArgumentException> {
                TeamAccessState.parse("3:7", record().apply { remove("baseGrants") })
            }
            val decision = TeamAccessState.parse("3:7", event)
            decision.allowedPermissions shouldBe setOf("administrator")
            ("transfer_team_ownership" in decision.allowedPermissions) shouldBe false
            event.putArray("baseGrants").add("project_read")
            TeamAccessState.parse("3:7", event).allowedPermissions shouldBe setOf("administrator", "project_read")
        }

        it("다른 계정의 키와 인가 버전이 없는 허용 판정을 거부한다") {
            shouldThrow<IllegalArgumentException> { TeamAccessState.parse("3:8", record()) }
            val event = record().apply { putNull("sourceAuthorizationVersion") }
            shouldThrow<IllegalArgumentException> { TeamAccessState.parse("3:7", event) }
            event.withObjectProperty("permissions").put("administrator", false)
            TeamAccessState.parse("3:7", event).allowedPermissions shouldBe emptySet()
        }
    })

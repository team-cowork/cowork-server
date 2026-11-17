package com.cowork.preference.domain.access.permission

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class RolePolicyTest :
    DescribeSpec({
        describe("역할 정책의 작업 권한 평가는") {
            it("관리자 정책으로 삭제와 채널 작업을 허용하지만 소유권 이양은 허용하지 않는다") {
                val admin = RolePolicy.administrator()
                RolePolicy.allows(admin, "delete_team") shouldBe true
                RolePolicy.allows(admin, "channel_manage") shouldBe true
                RolePolicy.allows(admin, "transfer_team_ownership") shouldBe false
            }
            it("소유자 정책은 관리자 권한과 소유권 이양을 허용한다") {
                RolePolicy.allows(RolePolicy.owner(), "delete_team") shouldBe true
                RolePolicy.allows(RolePolicy.owner(), "transfer_team_ownership") shouldBe true
            }
            it("세부 권한만 있는 역할도 해당 작업을 할 수 있다") {
                val policies = listOf(mapOf("delete_team" to true), mapOf("manage_team" to true))
                RolePolicy.allows(policies, "delete_team") shouldBe true
                RolePolicy.allows(policies, "manage_team") shouldBe true
                RolePolicy.allows(policies, "manage_roles") shouldBe false
            }
            it("알 수 없는 작업과 누락된 권한은 거부한다") {
                RolePolicy.allows(RolePolicy.owner(), "unknown_action") shouldBe false
                RolePolicy.allows(emptyMap<String, Boolean>(), "delete_team") shouldBe false
            }
        }
    })

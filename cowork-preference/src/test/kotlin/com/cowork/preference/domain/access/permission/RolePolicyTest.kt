package com.cowork.preference.domain.access.permission

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class RolePolicyTest :
    DescribeSpec({
        describe("RolePolicy 클래스의 allows 메서드는") {
            context("팀 역할에 생성 권한이나 관리자 권한이 있으면") {
                it("팀 생성을 허용한다") {
                    // Given
                    val policies = listOf(mapOf("create_team" to true), RolePolicy.administrator(), RolePolicy.owner())

                    // When
                    val allowed = policies.map { RolePolicy.allows(it, "create_team") }

                    // Then
                    allowed shouldBe listOf(true, true, true)
                }
            }
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

        describe("RolePolicy 클래스의 explain 메서드는") {
            val member = PolicyRole(1L, "@everyone", mapOf("manage_team" to false), isDefault = true)
            val admin = PolicyRole(2L, "Admin", RolePolicy.administrator())
            it("직접 허용한 역할이 있으면 ROLE_GRANT로 판정한다") {
                val decision = RolePolicy.explain(
                    listOf(member, PolicyRole(3L, "Lead", mapOf("manage_team" to true))),
                    "manage_team",
                )
                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ROLE_GRANT
                decision.sources.map { it.roleId to it.value } shouldBe listOf(1L to false, 3L to true)
            }
            it("관리자 역할만 허용하면 ADMINISTRATOR로 판정하고 관리자 역할을 근거에 포함한다") {
                val decision = RolePolicy.explain(listOf(member, admin), "manage_team")
                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ADMINISTRATOR
                decision.sources.last() shouldBe
                    PermissionSource(PermissionSourceKind.TEAM_ROLE, "administrator", true, 2L, "Admin")
            }
            it("관리자 역할도 소유권 이양은 허용하지 않는다") {
                val decision = RolePolicy.explain(listOf(admin), "transfer_team_ownership")
                decision.allowed shouldBe false
                decision.decidedBy shouldBe PermissionDecisionReason.NO_GRANT
            }
            it("모든 권한 조합에서 allows와 같은 결과를 낸다") {
                val roleSets = listOf(
                    emptyList(),
                    listOf(member),
                    listOf(admin),
                    listOf(PolicyRole(4L, "Owner", RolePolicy.owner())),
                    listOf(member, PolicyRole(5L, "Mixed", mapOf("delete_team" to true, "message_send" to true))),
                )
                roleSets.forEach { roles ->
                    (PermissionKeys.ROLE_SCOPED + "unknown_action").forEach { action ->
                        RolePolicy.explain(roles, action).allowed shouldBe
                            RolePolicy.allows(roles.map { it.permissions }, action)
                    }
                }
            }
        }
    })

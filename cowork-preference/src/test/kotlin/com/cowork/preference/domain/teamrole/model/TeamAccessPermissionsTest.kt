package com.cowork.preference.domain.teamrole.model

import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class TeamAccessPermissionsTest :
    DescribeSpec({
        describe("팀 권한 판정 결과") {
            fun role(vararg permissions: Pair<String, Boolean>) = PolicyRole(1L, "role", mapOf(*permissions))

            it("모든 TEAM_SCOPED 키의 결과를 담는다") {
                TeamAccessPermissions.resolve(emptyList(), active = true).keys shouldBe PermissionKeys.TEAM_SCOPED
            }
            it("역할 중 하나가 허용하면 다른 역할의 false와 관계없이 허용한다") {
                val result = TeamAccessPermissions.resolve(
                    listOf(role(PermissionKeys.MANAGE_TEAM to false), role(PermissionKeys.MANAGE_TEAM to true)),
                    active = true,
                )
                result[PermissionKeys.MANAGE_TEAM] shouldBe true
                result[PermissionKeys.KICK_MEMBERS] shouldBe false
            }
            it("administrator는 소유권 이양을 제외한 모든 팀 권한을 허용한다") {
                val result = TeamAccessPermissions.resolve(
                    listOf(role(PermissionKeys.ADMINISTRATOR to true)),
                    active = true,
                )
                result.filterValues { !it }.keys shouldBe setOf(PermissionKeys.TRANSFER_TEAM_OWNERSHIP)
            }
            it("프로젝트 생성과 역할 부여에 쓰는 기본 권한은 팀 권한과 별도로 발행한다") {
                val roles = listOf(role("project_read" to true, "message_send" to true, "manage_roles" to true))
                TeamAccessPermissions.baseGrants(roles, active = true) shouldBe listOf("message_send", "project_read")
                TeamAccessPermissions.baseGrants(roles, active = false) shouldBe emptyList()
                TeamAccessPermissions.resolve(roles, active = true).containsKey("project_read") shouldBe false
            }
            it("소유권 이양은 직접 허용할 때만 허용한다") {
                TeamAccessPermissions.resolve(
                    listOf(role(PermissionKeys.TRANSFER_TEAM_OWNERSHIP to true)),
                    active = true,
                )[PermissionKeys.TRANSFER_TEAM_OWNERSHIP] shouldBe true
            }
            it("활성 멤버가 아니면 역할과 관계없이 모든 권한을 거부한다") {
                val result = TeamAccessPermissions.resolve(
                    listOf(role(PermissionKeys.ADMINISTRATOR to true, PermissionKeys.TRANSFER_TEAM_OWNERSHIP to true)),
                    active = false,
                )
                result.keys shouldBe PermissionKeys.TEAM_SCOPED
                result.values.toSet() shouldBe setOf(false)
            }
        }
    })

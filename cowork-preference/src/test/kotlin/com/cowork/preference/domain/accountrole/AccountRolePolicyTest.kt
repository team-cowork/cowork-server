package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PolicyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class AccountRolePolicyTest :
    DescribeSpec({
        describe("AccountRolePolicy 클래스의 allows 메서드는") {
            context("기본 역할의 팀 생성 권한이 false이면") {
                it("팀 생성을 허용하지 않는다") {
                    // Given
                    val policies = listOf(mapOf("create_team" to false))

                    // When
                    val allowed = AccountRolePolicy.allows(policies, "create_team")

                    // Then
                    allowed shouldBe false
                }
            }

            context("기본 역할은 허용하지 않아도 부여된 역할이 허용하면") {
                it("팀 생성을 허용한다") {
                    // Given
                    val policies = listOf(mapOf("create_team" to false), mapOf("create_team" to true))

                    // When
                    val allowed = AccountRolePolicy.allows(policies, "create_team")

                    // Then
                    allowed shouldBe true
                }
            }
        }

        describe("AccountRolePolicy 클래스의 explain 메서드는") {
            it("허용한 역할이 있으면 ROLE_GRANT로 판정하고 모든 역할 값을 근거로 반환한다") {
                val roles = listOf(
                    PolicyRole(1L, "default", mapOf("create_team" to false), isDefault = true),
                    PolicyRole(2L, "creator", mapOf("create_team" to true)),
                )
                val decision = AccountRolePolicy.explain(roles, "create_team")
                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ROLE_GRANT
                decision.sources.map { it.roleName to it.value } shouldBe listOf("default" to false, "creator" to true)
            }
            it("허용한 역할이 없으면 NO_GRANT로 판정한다") {
                val decision = AccountRolePolicy.explain(listOf(PolicyRole(1L, "default", emptyMap())), "create_team")
                decision.allowed shouldBe false
                decision.decidedBy shouldBe PermissionDecisionReason.NO_GRANT
                decision.sources.single().value shouldBe null
            }
        }
    })

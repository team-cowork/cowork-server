package com.cowork.preference.domain.accountrole

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

            context("팀 관리자 권한만 있으면") {
                it("전역 팀 생성 권한으로 사용하지 않는다") {
                    // Given
                    val policies = listOf(mapOf("administrator" to true))

                    // When
                    val allowed = AccountRolePolicy.allows(policies, "create_team")

                    // Then
                    allowed shouldBe false
                }
            }
        }
    })

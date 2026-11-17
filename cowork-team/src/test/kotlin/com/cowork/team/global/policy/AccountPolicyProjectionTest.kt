package com.cowork.team.global.policy

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

class AccountPolicyProjectionTest :
    DescribeSpec({
        lateinit var jdbc: JdbcTemplate
        lateinit var projection: AccountPolicyProjection

        beforeEach {
            jdbc = mockk()
            projection = AccountPolicyProjection(jdbc, jacksonObjectMapper())
            every {
                jdbc.query(
                    match<String> { "FROM tb_account_policy_projections r" in it },
                    any<RowMapper<Map<String, Boolean>>>(),
                    7L,
                )
            } returns listOf(mapOf("create_team" to false))
        }

        describe("AccountPolicyProjection 클래스의 allows 메서드는") {
            context("기본 역할에만 팀 생성 거부가 있으면") {
                it("팀 생성을 거부한다") {
                    // Given
                    every {
                        jdbc.query(
                            match<String> { "FROM tb_team_role_projections r" in it },
                            any<RowMapper<Map<String, Boolean>>>(),
                            7L,
                        )
                    } returns listOf(mapOf("create_team" to false))

                    // When
                    val allowed = projection.allows(7L, "create_team")

                    // Then
                    allowed shouldBe false
                }
            }

            listOf(
                "생성 권한" to mapOf("create_team" to true),
                "관리자 정책" to RolePolicy.administrator(),
                "소유자 정책" to RolePolicy.owner(),
            ).forEach { (name, policy) ->
                context("부여된 팀 역할에 $name 이 있으면") {
                    it("기본 역할이 허용하지 않아도 팀 생성을 허용한다") {
                        // Given
                        every {
                            jdbc.query(
                                match<String> { "FROM tb_team_role_projections r" in it },
                                any<RowMapper<Map<String, Boolean>>>(),
                                7L,
                            )
                        } returns listOf(mapOf("create_team" to false), policy)

                        // When
                        val allowed = projection.allows(7L, "create_team")

                        // Then
                        allowed shouldBe true
                    }
                }
            }

            context("부여된 전역 역할에 팀 생성 권한이 있으면") {
                it("가입한 팀이 없어도 팀 생성을 허용한다") {
                    // Given
                    every {
                        jdbc.query(
                            match<String> { "FROM tb_account_policy_projections r" in it },
                            any<RowMapper<Map<String, Boolean>>>(),
                            7L,
                        )
                    } returns listOf(mapOf("create_team" to false), mapOf("create_team" to true))
                    every {
                        jdbc.query(
                            match<String> { "FROM tb_team_role_projections r" in it },
                            any<RowMapper<Map<String, Boolean>>>(),
                            7L,
                        )
                    } returns emptyList()

                    // When
                    val allowed = projection.allows(7L, "create_team")

                    // Then
                    allowed shouldBe true
                }
            }

            context("전역 역할 관리 권한이 없으면") {
                it("팀 역할로 전역 역할 관리를 허용하지 않는다") {
                    // Given
                    val action = "manage_account_roles"

                    // When
                    val allowed = projection.allows(7L, action)

                    // Then
                    allowed shouldBe false
                    verify(exactly = 0) {
                        jdbc.query(
                            match<String> { "FROM tb_team_role_projections r" in it },
                            any<RowMapper<Map<String, Boolean>>>(),
                            any<Long>(),
                        )
                    }
                }
            }
        }
    })

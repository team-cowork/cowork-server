package com.cowork.team.domain.team.service

import com.cowork.team.global.policy.AccountPolicyProjection
import com.cowork.team.global.policy.RolePolicy
import com.cowork.team.global.projection.ProjectionReadinessGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class AccountPermissionGuardTest :
    DescribeSpec({
        lateinit var accounts: AccountPolicyProjection
        lateinit var readiness: ProjectionReadinessGate
        lateinit var guard: AccountPermissionGuard

        beforeEach {
            accounts = mockk()
            readiness = mockk()
            guard = AccountPermissionGuard(accounts, readiness)
            every { readiness.requireReady() } returns Unit
            every { accounts.findAccountPolicies(7) } returns listOf(mapOf("create_team" to false))
            every { accounts.findTeamPolicies(7) } returns listOf(mapOf("create_team" to false))
        }

        describe("AccountPermissionGuard 클래스의 requireCreateTeam 메서드는") {
            context("기본 역할이 팀 생성을 허용하지 않으면") {
                it("팀 생성을 거부한다") {
                    // Given
                    val accountId = 7L

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(accountId) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            listOf(
                "생성 권한" to mapOf("create_team" to true),
                "관리자 정책" to RolePolicy.administrator(),
                "소유자 정책" to RolePolicy.owner(),
            ).forEach { (name, policy) ->
                context("부여된 팀 역할에 ${name}이 있으면") {
                    it("기본 역할이 허용하지 않아도 팀 생성을 허용한다") {
                        // Given
                        every { accounts.findTeamPolicies(7) } returns listOf(mapOf("create_team" to false), policy)

                        // When
                        guard.requireCreateTeam(7)

                        // Then
                        verify(exactly = 1) { accounts.findAccountPolicies(7) }
                        verify(exactly = 1) { accounts.findTeamPolicies(7) }
                    }
                }
            }

            context("부여된 전역 역할에 팀 생성 권한이 있으면") {
                it("가입한 팀이 없어도 팀 생성을 허용한다") {
                    // Given
                    every { accounts.findAccountPolicies(7) } returns listOf(
                        mapOf("create_team" to false),
                        mapOf("create_team" to true),
                    )
                    every { accounts.findTeamPolicies(7) } returns emptyList()

                    // When
                    guard.requireCreateTeam(7)

                    // Then
                    verify(exactly = 1) { accounts.findAccountPolicies(7) }
                    verify(exactly = 1) { accounts.findTeamPolicies(7) }
                }
            }

            context("기본 역할에서 팀 생성을 허용하면") {
                it("명시적으로 부여된 역할이 없어도 팀 생성을 허용한다") {
                    // Given
                    every { accounts.findAccountPolicies(7) } returns listOf(mapOf("create_team" to true))
                    every { accounts.findTeamPolicies(7) } returns emptyList()

                    // When
                    guard.requireCreateTeam(7)

                    // Then
                    verify(exactly = 1) { accounts.findAccountPolicies(7) }
                }
            }

            context("적용되는 역할 정책이 없으면") {
                it("팀 생성을 거부한다") {
                    // Given
                    every { accounts.findAccountPolicies(7) } returns emptyList()
                    every { accounts.findTeamPolicies(7) } returns emptyList()

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(7) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("다른 작업의 권한만 있으면") {
                it("팀 생성을 거부한다") {
                    // Given
                    every { accounts.findAccountPolicies(7) } returns listOf(mapOf("manage_account_roles" to true))
                    every { accounts.findTeamPolicies(7) } returns emptyList()

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(7) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("정책 동기화가 완료되지 않았으면") {
                it("정책 조회 전에 요청을 거부한다") {
                    // Given
                    every { readiness.requireReady() } throws
                        ExpectedException("동기화 중입니다.", HttpStatus.SERVICE_UNAVAILABLE)

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(7) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    verify(exactly = 0) { accounts.findAccountPolicies(any()) }
                    verify(exactly = 0) { accounts.findTeamPolicies(any()) }
                }
            }
        }
    })

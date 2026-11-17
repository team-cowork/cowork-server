package com.cowork.team.domain.team.service

import com.cowork.team.global.policy.AccountPolicyProjection
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
        }

        describe("AccountPermissionGuard 클래스의 requireCreateTeam 메서드는") {
            context("적용된 역할 정책이 팀 생성 권한을 허용하면") {
                it("팀 생성을 허용한다") {
                    // Given
                    every { accounts.allows(7L, "create_team") } returns true

                    // When
                    guard.requireCreateTeam(7L)

                    // Then
                    verify(exactly = 1) { accounts.allows(7L, "create_team") }
                }
            }

            context("적용된 역할 정책에 팀 생성 권한이 없으면") {
                it("팀 생성을 거부한다") {
                    // Given
                    every { accounts.allows(7L, "create_team") } returns false

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(7L) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("정책 동기화가 완료되지 않았으면") {
                it("권한 평가 전에 요청을 거부한다") {
                    // Given
                    every { readiness.requireReady() } throws
                        ExpectedException("동기화 중입니다.", HttpStatus.SERVICE_UNAVAILABLE)

                    // When
                    val exception = shouldThrow<ExpectedException> { guard.requireCreateTeam(7L) }

                    // Then
                    exception.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    verify(exactly = 0) { accounts.allows(any(), any()) }
                }
            }
        }
    })

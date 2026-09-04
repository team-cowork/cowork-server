package com.cowork.channel.domain.channelRolePolicy.service.support

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class ChannelRolePolicyAccessSupportTest :
    DescribeSpec({

        lateinit var roleRepository: TeamRoleProjectionRepository
        lateinit var policyRepository: ChannelRolePolicyProjectionRepository
        lateinit var support: ChannelRolePolicyAccessSupport
        val occurredAt = Instant.parse("2026-08-30T01:00:00Z")

        beforeEach {
            roleRepository = mockk()
            policyRepository = mockk()
            support = ChannelRolePolicyAccessSupport(
                roleRepository,
                policyRepository,
                mockk<ProjectionReadinessGate>(relaxed = true),
            )
        }

        describe("ChannelRolePolicyAccessSupport 클래스의 requireRole 메서드는") {
            context("역할이 다른 팀에 속하면") {
                it("NOT_FOUND로 거부한다") {
                    every { roleRepository.findById(7L) } returns Optional.of(
                        TeamRoleProjection(7L, 200L, 10, sourceOccurredAt = occurredAt),
                    )

                    val error = shouldThrow<ExpectedException> { support.requireRole(100L, 7L) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            context("같은 팀의 활성 역할이면") {
                it("접근을 허용한다") {
                    every { roleRepository.findById(7L) } returns Optional.of(
                        TeamRoleProjection(7L, 100L, 10, sourceOccurredAt = occurredAt),
                    )

                    shouldNotThrowAny { support.requireRole(100L, 7L) }
                }
            }
        }

        describe("ChannelRolePolicyAccessSupport 클래스의 requirePolicy 메서드는") {
            context("정책이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    every { policyRepository.findById("policy:100:1:7") } returns Optional.empty()

                    val error = shouldThrow<ExpectedException> { support.requirePolicy(100L, 1L, 7L) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            context("활성 정책이 있으면") {
                it("접근을 허용한다") {
                    every { policyRepository.findById("policy:100:1:7") } returns Optional.of(
                        ChannelRolePolicyProjection(
                            policyKey = "policy:100:1:7",
                            teamId = 100L,
                            channelId = 1L,
                            roleId = 7L,
                            messageRead = true,
                            sourceOccurredAt = occurredAt,
                        ),
                    )

                    shouldNotThrowAny { support.requirePolicy(100L, 1L, 7L) }
                }
            }
        }
    })

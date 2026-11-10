package com.cowork.channel.domain.project.entity

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ProjectAccessProjectionTest :
    DescribeSpec({

        val authorizationVersion = Instant.parse("2026-11-01T00:00:00Z")

        fun access(canRead: Boolean = true, canChannelArchive: Boolean = true) = ProjectAccessProjection(
            accessKey = "5:7",
            projectId = 5L,
            userId = 7L,
            canRead = canRead,
            canChannelArchive = canChannelArchive,
            sourceOccurredAt = Instant.parse("2026-11-10T00:00:00Z"),
            sourceMembershipVersion = authorizationVersion,
            sourceAuthorizationVersion = authorizationVersion,
        )

        describe("ProjectAccessProjection 클래스의 allowsChannelArchive 메서드는") {
            context("읽기와 보관 권한이 있고 현재 권한 버전으로 계산된 값이면") {
                it("채널 보관을 허용한다") {
                    access().allowsChannelArchive(authorizationVersion) shouldBe true
                }
            }

            context("읽기나 보관 권한이 없으면") {
                it("채널 보관을 허용하지 않는다") {
                    access(canRead = false).allowsChannelArchive(authorizationVersion) shouldBe false
                    access(canChannelArchive = false).allowsChannelArchive(authorizationVersion) shouldBe false
                }
            }

            context("팀 멤버십의 권한 버전과 다르거나 권한 버전이 없으면") {
                it("오래된 권한으로 보고 채널 보관을 허용하지 않는다") {
                    access().allowsChannelArchive(authorizationVersion.plusSeconds(1)) shouldBe false
                    access().allowsChannelArchive(null) shouldBe false
                }
            }
        }
    })

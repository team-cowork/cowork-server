package com.cowork.project.domain.project.entity

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ProjectAccessProjectionTest :
    DescribeSpec({
        val version = Instant.parse("2026-10-25T00:00:00Z")
        fun access(readPrivate: Boolean, membership: Instant = version, authorization: Instant? = version) =
            ProjectAccessProjection(
                "3:20",
                3L,
                20L,
                true,
                false,
                version.plusSeconds(1),
                sourceMembershipVersion = membership,
                sourceAuthorizationVersion = authorization,
                canReadPrivate = readPrivate,
            )

        describe("ProjectAccessProjection 클래스의 privateReadForSameVersion 메서드는") {
            it("동일한 권한 버전에서 양쪽이 허용한 비공개 읽기만 유지한다") {
                access(true).privateReadForSameVersion(access(true)) shouldBe true
                access(false).privateReadForSameVersion(access(true)) shouldBe false
                access(true).privateReadForSameVersion(access(false)) shouldBe false
            }

            it("멤버십이나 권한 버전이 다르면 접근을 거부한다") {
                access(true).privateReadForSameVersion(access(true, membership = version.plusSeconds(1))) shouldBe false
                access(true).privateReadForSameVersion(access(true, authorization = version.minusSeconds(1))) shouldBe
                    false
            }

            it("권한 버전이 없는 회수 상태를 같은 버전의 허용으로 복구하지 않는다") {
                access(true, authorization = null).privateReadForSameVersion(access(true)) shouldBe false
                access(true).privateReadForSameVersion(access(true, authorization = null)) shouldBe false
                access(
                    true,
                    authorization = null,
                ).privateReadForSameVersion(access(true, authorization = null)) shouldBe
                    false
            }
        }
    })

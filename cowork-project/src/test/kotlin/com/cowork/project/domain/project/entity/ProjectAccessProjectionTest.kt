package com.cowork.project.domain.project.entity

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ProjectAccessProjectionTest : DescribeSpec({
    val version = Instant.parse("2026-10-25T00:00:00Z")
    fun access(readPrivate: Boolean?, membership: Instant = version, authorization: Instant? = version) =
        ProjectAccessProjection(
            "3:20", 3L, 20L, true, false, version.plusSeconds(1),
            sourceMembershipVersion = membership, sourceAuthorizationVersion = authorization, canReadPrivate = readPrivate,
        )

    describe("ProjectAccessProjection 클래스의 privateReadForSameVersion 메서드는") {
        it("구형 이벤트의 미확인 값은 같은 버전의 명시 값으로 보정하고 늦게 도착한 누락은 무시한다") {
            access(null).privateReadForSameVersion(access(null)) shouldBe null
            access(null).privateReadForSameVersion(access(true)) shouldBe true
            access(true).privateReadForSameVersion(access(null)) shouldBe true
            access(null).privateReadForSameVersion(access(false)) shouldBe false
        }

        it("명시적 거부는 같은 버전의 허용이나 구형 이벤트로 복원하지 않는다") {
            access(false).privateReadForSameVersion(access(true)) shouldBe false
            access(true).privateReadForSameVersion(access(false)) shouldBe false
            access(false).privateReadForSameVersion(access(null)) shouldBe false
        }

        it("멤버십이나 권한 버전 충돌은 미확인 값을 보정하지 않고 거부로 고정한다") {
            val membershipMismatch = access(null)
            membershipMismatch.canReadPrivate = membershipMismatch.privateReadForSameVersion(access(true, membership = version.plusSeconds(1)))
            membershipMismatch.canReadPrivate shouldBe false
            membershipMismatch.privateReadForSameVersion(access(true)) shouldBe false

            access(null).privateReadForSameVersion(access(true, authorization = version.minusSeconds(1))) shouldBe false
        }
    }
})

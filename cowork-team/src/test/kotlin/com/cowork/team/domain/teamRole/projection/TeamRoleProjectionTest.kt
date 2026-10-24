package com.cowork.team.domain.teamRole.projection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class TeamRoleProjectionTest :
    DescribeSpec({
        it("기본 역할 여부는 구버전 상태가 뒤늦게 도착해도 일반 역할로 바뀌지 않는다") {
            val version = Instant.parse("2026-10-25T00:00:00Z")
            val role = TeamRoleProjection(1L, 2L, sourceCreatedAt = version, sourceOccurredAt = version)
            role.applyUpsert("@everyone", "#99AAB5", Int.MIN_VALUE, false, "{}", version, isDefault = true)
            role.applyUpsert("@everyone", "#99AAB5", Int.MIN_VALUE, false, "{}", version.plusSeconds(1))
            role.isDefault shouldBe true
        }
    })

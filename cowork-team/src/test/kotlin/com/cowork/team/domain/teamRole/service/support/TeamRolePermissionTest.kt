package com.cowork.team.domain.teamRole.service.support

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class TeamRolePermissionTest :
    DescribeSpec({
        describe("TeamRolePermission 클래스의 KEYS는") {
            it("권한마다 서로 다른 키를 가진다") {
                TeamRolePermission.KEYS.size shouldBe TeamRolePermission.entries.size
            }
        }
    })

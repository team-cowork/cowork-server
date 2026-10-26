package com.cowork.team.domain.teamRole.service.support

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class TeamRolePermissionTest :
    DescribeSpec({
        describe("TeamRolePermission 카탈로그는") {
            it("권한마다 서로 다른 키를 가진다") {
                TeamRolePermission.KEYS.size shouldBe TeamRolePermission.entries.size
            }

            it("모든 그룹에 권한이 하나 이상 속한다") {
                TeamRolePermission.entries.map { it.group }.toSet() shouldContainAll TeamRolePermissionGroup.entries
            }
        }
    })

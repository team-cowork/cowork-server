package com.cowork.team

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.modulith.core.ApplicationModules

class TeamModuleStructureTest :
    DescribeSpec({
        val modules = ApplicationModules.of("com.cowork.team.domain")
        val invites = modules.getModuleByName("teamInvite").orElseThrow()
        val members = modules.getModuleByName("teamMember").orElseThrow()

        describe("팀 초대와 멤버 모듈의 의존성") {
            it("멤버는 초대 모듈을 참조하지 않는다") {
                members.getDirectDependencies(modules).stream()
                    .filter { it.targetModule == invites }
                    .map { "${it.sourceType.name} -> ${it.targetType.name}" }
                    .toList().shouldBeEmpty()
            }

            it("초대는 멤버 모듈의 공개 API만 사용한다") {
                invites.getDirectDependencies(modules).stream()
                    .filter { it.targetModule == members && !members.isExposed(it.targetType) }
                    .map { "${it.sourceType.name} -> ${it.targetType.name}" }
                    .toList().shouldBeEmpty()
            }
        }
    })

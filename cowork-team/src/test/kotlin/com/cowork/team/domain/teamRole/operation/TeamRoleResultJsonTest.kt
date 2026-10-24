package com.cowork.team.domain.teamRole.operation

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper

class TeamRoleResultJsonTest :
    DescribeSpec({
        val mapper = jacksonObjectMapper()
        val current = """{"id":1,"isDefault":false,"permissions":{"read":true,"write":false}}"""

        describe("팀 역할 결과의 멱등성 비교는") {
            it("기존 사용자 역할의 누락된 isDefault를 false와 동일하게 취급한다") {
                val legacy = """{"id":1,"permissions":{"read":true,"write":false}}"""
                TeamRoleResultJson.matches(legacy, current, mapper) shouldBe true
            }
            it("보정 과정의 공백과 JSON 키 순서 변경을 허용한다") {
                val repaired = """{ "permissions": {"write": false, "read": true}, "isDefault": false, "id": 1 }"""
                TeamRoleResultJson.matches(repaired, current, mapper) shouldBe true
                TeamRoleResultJson.matches(current, current, mapper) shouldBe true
            }
            it("기본 역할 여부가 다르거나 명시적 null이면 충돌로 유지한다") {
                val defaultRole = current.replace("\"isDefault\":false", "\"isDefault\":true")
                val unknownRole = current.replace("\"isDefault\":false", "\"isDefault\":null")
                TeamRoleResultJson.matches(defaultRole, current, mapper) shouldBe false
                TeamRoleResultJson.matches(unknownRole, current, mapper) shouldBe false
            }
            it("다른 역할 식별자나 권한 변경은 충돌로 유지한다") {
                TeamRoleResultJson.matches(current.replace("\"id\":1", "\"id\":2"), current, mapper) shouldBe false
                TeamRoleResultJson.matches(current.replace("\"read\":true", "\"read\":false"), current, mapper) shouldBe
                    false
            }
            it("projection이 기본 역할임을 확인하면 구버전이 누락하거나 false로 보정한 유형을 복구한다") {
                val legacy = """{"id":1,"permissions":{"read":true,"write":false}}"""
                val defaultRole = current.replace("\"isDefault\":false", "\"isDefault\":true")
                TeamRoleResultJson.matches(legacy, defaultRole, mapper, knownDefaultRole = true) shouldBe true
                TeamRoleResultJson.matches(current, defaultRole, mapper, knownDefaultRole = true) shouldBe true
                TeamRoleResultJson.matches(current, defaultRole, mapper) shouldBe false
                val differentRole = defaultRole.replace("\"id\":1", "\"id\":2")
                TeamRoleResultJson.matches(current, differentRole, mapper, knownDefaultRole = true) shouldBe false
            }
            it("역할 없는 삭제 결과와 역할 있는 결과를 구분한다") {
                TeamRoleResultJson.matches(null, null, mapper) shouldBe true
                TeamRoleResultJson.matches(null, current, mapper) shouldBe false
                TeamRoleResultJson.matches(current, null, mapper) shouldBe false
            }
        }
    })

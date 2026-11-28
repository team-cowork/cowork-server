package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EffectivePermissionQueryTest :
    DescribeSpec({
        fun parse(vararg params: Pair<String, String>) = EffectivePermissionQuery.parse(mapOf(*params)::get)

        describe("EffectivePermissionQuery 클래스의 parse 메서드는") {
            it("범위 파라미터가 없으면 계정 범위의 모든 권한을 조회한다") {
                val query = parse()
                query.scope shouldBe PermissionScope.Account
                query.explain shouldBe false
                query.keys shouldBe PermissionScope.Account.keys
            }
            it("지정한 ID에 맞는 범위를 만든다") {
                parse("teamId" to "3").scope shouldBe PermissionScope.Team(3)
                parse("channelId" to "45").scope shouldBe PermissionScope.Channel(45)
                parse("projectId" to "7").scope shouldBe PermissionScope.Project(7)
                parse("teamId" to "3").keys shouldBe PermissionKeys.TEAM_SCOPED.toList()
            }
            it("권한 필터를 공백 없이 읽고 범위의 정의 순서로 반환한다") {
                val query = parse(
                    "channelId" to "45",
                    "permissions" to " message_send, ,message_read ",
                    "explain" to "true",
                )
                query.keys shouldBe listOf("message_read", "message_send")
                query.explain shouldBe true
            }
            it("범위를 둘 이상 지정하면 거부한다") {
                shouldThrow<IllegalArgumentException> { parse("teamId" to "3", "channelId" to "45") }
            }
            it("양수가 아닌 ID를 거부한다") {
                listOf("0", "-1", "abc").forEach { shouldThrow<IllegalArgumentException> { parse("teamId" to it) } }
            }
            it("범위에 없는 권한 키를 거부한다") {
                shouldThrow<IllegalArgumentException> { parse("teamId" to "3", "permissions" to "message_send") }
                shouldThrow<IllegalArgumentException> { parse("permissions" to "unknown") }
            }
            it("explain 값은 true 또는 false만 허용한다") {
                parse("explain" to "false").explain shouldBe false
                shouldThrow<IllegalArgumentException> { parse("explain" to "yes") }
            }
        }
    })

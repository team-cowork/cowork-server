package com.cowork.preference.domain.access.permission

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

class RolePermissionTest :
    DescribeSpec({
        describe("역할 권한 카탈로그") {
            it("저장할 수 있는 모든 권한을 중복 없이 설명한다") {
                val permissions = RolePermission.catalog().flatMap { group ->
                    (group as JsonObject).getJsonArray("permissions").map { it as JsonObject }
                }

                permissions.map { it.getString("key") }.toSet() shouldBe PermissionKeys.ROLE_SCOPED
                permissions.size shouldBe PermissionKeys.ROLE_SCOPED.size
                permissions.forEach { permission ->
                    permission.getString("title").isNotBlank() shouldBe true
                    permission.getString("description").isNotBlank() shouldBe true
                    permission.fieldNames() shouldBe setOf("key", "title", "description", "channelTypes")
                }
            }

            it("기존 그룹 순서와 채널별 권한 적용 범위를 유지한다") {
                val groups = RolePermission.catalog().map { it as JsonObject }

                groups.map { it.getString("key") } shouldBe
                    listOf(
                        "TEAM",
                        "MEMBER",
                        "CHANNEL",
                        "TEXT_CHANNEL",
                        "VOICE_CHANNEL",
                        "PROJECT",
                        "CALENDAR",
                        "ROADMAP",
                    )
                val voice = groups.single { it.getString("key") == "VOICE_CHANNEL" }.getJsonArray("permissions")
                voice.map { (it as JsonObject).getString("key") } shouldBe
                    listOf("voice_connect", "voice_speak", "screen_share", "live_broadcast", "voice_log_read")
                voice.forEach { (it as JsonObject).getJsonArray("channelTypes").list shouldBe listOf("VOICE") }
            }
        }

        describe("역할 권한 카탈로그 검색") {
            fun keysByGroup(catalog: JsonArray): Map<String, List<String>> = catalog.map { it as JsonObject }
                .associate { group ->
                    group.getString("key") to
                        group.getJsonArray("permissions").map { (it as JsonObject).getString("key") }
                }

            it("조건이 없거나 공백뿐인 검색어는 전체 카탈로그를 그대로 돌려준다") {
                RolePermission.catalog(query = null) shouldBe RolePermission.catalog()
                RolePermission.catalog(query = "   ") shouldBe RolePermission.catalog()
            }

            it("권한 제목, 설명, 키 중 하나가 일치하는 권한만 그룹 순서대로 돌려주고 빈 그룹은 뺀다") {
                val byTitle = keysByGroup(RolePermission.catalog(query = " ${RolePermission.VOICE_SPEAK.title} "))
                byTitle.values.flatten() shouldContain RolePermission.VOICE_SPEAK.key
                byTitle.values.forEach { it.isNotEmpty() shouldBe true }

                val byKey = keysByGroup(RolePermission.catalog(query = "VOICE_LOG_READ"))
                byKey shouldBe mapOf("VOICE_CHANNEL" to listOf("voice_log_read"))

                val term = "메시지"
                val expected = RolePermissionGroup.entries.associate { group ->
                    group.name to RolePermission.entries.filter {
                        it.group == group &&
                            (it.title.contains(term) || it.description.contains(term) || it.key.contains(term))
                    }.map { it.key }
                }.filterValues { it.isNotEmpty() }
                val result = keysByGroup(RolePermission.catalog(query = term))
                result shouldBe expected
                result.keys.toList() shouldBe RolePermissionGroup.entries.map { it.name }.filter { it in expected }
            }

            it("그룹 제목이 일치하면 그 그룹의 권한을 모두 돌려준다") {
                val all = keysByGroup(RolePermission.catalog())
                val result = keysByGroup(RolePermission.catalog(query = RolePermissionGroup.VOICE_CHANNEL.title))

                result.getValue("VOICE_CHANNEL") shouldBe all.getValue("VOICE_CHANNEL")
            }

            it("채널 종류가 있으면 그 채널에 적용되는 권한만 돌려준다") {
                val result = RolePermission.catalog(channelType = RolePermissionChannelType.TEXT)
                val permissions = result.flatMap { (it as JsonObject).getJsonArray("permissions") }
                    .map { it as JsonObject }

                permissions.map { it.getString("key") } shouldBe
                    RolePermission.entries.filter { RolePermissionChannelType.TEXT in it.channelTypes }.map { it.key }
                result.forEach { (it as JsonObject).getJsonArray("permissions").isEmpty shouldBe false }
                keysByGroup(result).keys shouldNotContain "VOICE_CHANNEL"
            }

            it("일치하는 권한이 없으면 빈 배열을 돌려준다") {
                RolePermission.catalog(query = "존재하지않는권한검색어").isEmpty shouldBe true
            }
        }
    })

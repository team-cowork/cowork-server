package com.cowork.preference.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
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
                    listOf("TEAM", "MEMBER", "CHANNEL", "TEXT_CHANNEL", "VOICE_CHANNEL", "PROJECT")
                val voice = groups.single { it.getString("key") == "VOICE_CHANNEL" }.getJsonArray("permissions")
                voice.map { (it as JsonObject).getString("key") } shouldBe
                    listOf("voice_connect", "voice_speak", "screen_share", "live_broadcast")
                voice.forEach { (it as JsonObject).getJsonArray("channelTypes").list shouldBe listOf("VOICE") }
            }
        }
    })

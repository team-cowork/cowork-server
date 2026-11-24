package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.vertx.core.json.JsonObject
import java.time.Instant

class EffectivePermissionResultTest :
    DescribeSpec({
        val decision = PermissionDecision(
            false,
            PermissionDecisionReason.ROLE_OVERWRITE_DENY,
            listOf(PermissionSource(PermissionSourceKind.CHANNEL_ROLE_OVERWRITE, "message_send", false, 7L, "Guest")),
        )

        fun result(explain: Boolean) = EffectivePermissionResult(
            accountId = 12,
            scope = PermissionScope.Channel(45),
            teamId = 3,
            evaluatedAt = Instant.parse("2026-11-24T05:12:00Z"),
            decisions = mapOf("message_send" to decision),
            explain = explain,
            channelArchived = true,
        )

        describe("EffectivePermissionResult 클래스의 toJson 메서드는") {
            it("범위와 권한 값을 반환하고 explain이 false이면 근거를 생략한다") {
                result(explain = false).toJson() shouldBe JsonObject(
                    """
                    {
                      "accountId": 12,
                      "scope": { "type": "CHANNEL", "teamId": 3, "channelId": 45, "archived": true },
                      "evaluatedAt": "2026-11-24T05:12:00Z",
                      "permissions": { "message_send": false }
                    }
                    """.trimIndent(),
                )
            }
            it("explain이 true이면 권한별 판정 근거를 포함한다") {
                result(explain = true).toJson().getJsonObject("explanations") shouldBe JsonObject(
                    """
                    {
                      "message_send": {
                        "allowed": false,
                        "decidedBy": "ROLE_OVERWRITE_DENY",
                        "sources": [
                          {
                            "kind": "CHANNEL_ROLE_OVERWRITE",
                            "teamId": null,
                            "roleId": 7,
                            "roleName": "Guest",
                            "permission": "message_send",
                            "value": false
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
            }
        }
    })

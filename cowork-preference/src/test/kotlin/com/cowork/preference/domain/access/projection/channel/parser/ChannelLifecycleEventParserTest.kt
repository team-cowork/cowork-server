package com.cowork.preference.domain.access.projection.channel.parser

import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleRecordDecision
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.vertx.core.json.JsonObject

private fun json(vararg overrides: Pair<String, Any?>): String {
    val fields = mutableMapOf<String, Any?>(
        "eventType" to "UPDATED",
        "channelId" to 7,
        "teamId" to 42,
        "occurredAt" to "2026-10-13T01:00:00Z",
        "isPrivate" to true,
        "type" to "VOICE",
    )
    overrides.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = value }
    return JsonObject(fields).encode()
}

class ChannelLifecycleEventParserTest :
    DescribeSpec({
        describe("ChannelLifecycleEventParser 객체의") {
            context("비공개 여부와 유형이 있는 이벤트이면") {
                it("두 값을 그대로 해석한다") {
                    val event = ChannelLifecycleEventParser.parse("7", json())
                        .shouldBeInstanceOf<ChannelLifecycleRecordDecision.Apply>().event

                    event.isPrivate shouldBe true
                    event.channelType shouldBe "VOICE"
                }
            }

            context("isPrivate가 없거나 boolean이 아니면") {
                it("잘못된 레코드로 격리한다") {
                    listOf(json("isPrivate" to null), json("isPrivate" to "true")).forEach { value ->
                        ChannelLifecycleEventParser.parse("7", value)
                            .shouldBeInstanceOf<ChannelLifecycleRecordDecision.Quarantine>()
                    }
                }
            }

            context("type이 없거나 빈 문자열이면") {
                it("channelType이 있어도 잘못된 레코드로 격리한다") {
                    listOf(json("type" to null, "channelType" to "TEXT"), json("type" to " ")).forEach { value ->
                        ChannelLifecycleEventParser.parse("7", value)
                            .shouldBeInstanceOf<ChannelLifecycleRecordDecision.Quarantine>()
                    }
                }
            }
        }
    })

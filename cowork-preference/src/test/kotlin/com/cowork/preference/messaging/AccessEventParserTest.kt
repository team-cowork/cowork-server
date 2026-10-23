package com.cowork.preference.messaging

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.vertx.core.json.JsonObject

private const val OCCURRED_AT = "2026-10-13T01:00:00Z"

private fun json(vararg fields: Pair<String, Any?>): String = JsonObject(mapOf(*fields)).encode()

class AccessEventParserTest :
    DescribeSpec({
        describe("DmParticipantEventParser 객체의") {
            context("JOIN 이벤트이면") {
                it("활성 DM 참여으로 해석한다") {
                    val value = json(
                        "eventType" to "JOIN",
                        "channelId" to 11,
                        "teamId" to null,
                        "channelType" to "DM",
                        "userId" to 100,
                        "occurredAt" to OCCURRED_AT,
                    )

                    val event = DmParticipantEventParser.parse("11:100", value)
                        .shouldBeInstanceOf<DmParticipantRecordDecision.Apply>().event

                    event.channelId shouldBe 11L
                    event.userId shouldBe 100L
                    event.deleted shouldBe false
                }
            }

            context("LEAVE 이벤트이면") {
                it("삭제된 DM 참여으로 해석해 권한을 회수한다") {
                    val value = json(
                        "eventType" to "LEAVE",
                        "channelId" to 11,
                        "teamId" to null,
                        "channelType" to "DM",
                        "userId" to 100,
                        "occurredAt" to OCCURRED_AT,
                    )

                    DmParticipantEventParser.parse("11:100", value)
                        .shouldBeInstanceOf<DmParticipantRecordDecision.Apply>().event.deleted shouldBe true
                }
            }

            context("key가 channelId와 userId 조합과 다르면") {
                it("다른 채널의 DM 참여을 바꾸지 못하도록 격리한다") {
                    val value = json("eventType" to "JOIN", "channelId" to 11, "userId" to 100, "occurredAt" to OCCURRED_AT)

                    DmParticipantEventParser.parse("12:100", value)
                        .shouldBeInstanceOf<DmParticipantRecordDecision.Quarantine>()
                }
            }
        }

        describe("ProjectLifecycleEventParser 객체의") {
            context("CREATED·UPDATED 이벤트이면") {
                it("소속 팀을 가진 활성 프로젝트로 해석한다") {
                    val value = json(
                        "eventType" to "UPDATED",
                        "projectId" to 7,
                        "teamId" to 42,
                        "occurredAt" to OCCURRED_AT,
                    )

                    val event = ProjectLifecycleEventParser.parse("7", value)
                        .shouldBeInstanceOf<ProjectLifecycleRecordDecision.Apply>().event

                    event.teamId shouldBe 42L
                    event.deleted shouldBe false
                }
            }

            context("DELETED 이벤트이면") {
                it("삭제된 프로젝트로 해석해 권한을 회수한다") {
                    val value = json("eventType" to "DELETED", "projectId" to 7, "teamId" to 42, "occurredAt" to OCCURRED_AT)

                    ProjectLifecycleEventParser.parse("7", value)
                        .shouldBeInstanceOf<ProjectLifecycleRecordDecision.Apply>().event.deleted shouldBe true
                }
            }

            context("key가 projectId와 다르면") {
                it("다른 프로젝트의 소속 팀을 바꾸지 못하도록 격리한다") {
                    val value = json("eventType" to "UPDATED", "projectId" to 7, "teamId" to 42, "occurredAt" to OCCURRED_AT)

                    ProjectLifecycleEventParser.parse("8", value)
                        .shouldBeInstanceOf<ProjectLifecycleRecordDecision.Quarantine>()
                }
            }
        }
    })

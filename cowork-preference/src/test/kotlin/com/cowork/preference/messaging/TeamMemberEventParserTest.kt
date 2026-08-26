package com.cowork.preference.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TeamMemberEventParserTest {

    @Test
    fun `parses a keyed member delete with an RFC3339 instant`() {
        val decision = TeamMemberEventParser.parse(
            key = "3:11",
            value =
            """
                {
                  "eventType":"DELETE",
                  "teamId":3,
                  "userId":11,
                  "role":"MEMBER",
                  "occurredAt":"2026-08-26T01:02:03Z"
                }
            """.trimIndent(),
        )

        val event = assertInstanceOf(TeamMemberRecordDecision.ApplyDelete::class.java, decision).event
        assertEquals(3L, event.teamId)
        assertEquals(11L, event.userId)
        assertEquals(TeamMemberCleanupScope.MEMBER, TeamMemberCleanupPolicy.scope(event))
        assertEquals(Instant.parse("2026-08-26T01:02:03Z"), event.occurredAt)
    }

    @Test
    fun `owner delete represents team deletion and cleans the whole team role model`() {
        val decision = TeamMemberEventParser.parse(
            key = "3:7",
            value =
            """
                {
                  "eventType":"DELETE",
                  "teamId":3,
                  "userId":7,
                  "role":"OWNER",
                  "occurredAt":"2026-08-26T01:02:03Z"
                }
            """.trimIndent(),
        )

        val event = assertInstanceOf(TeamMemberRecordDecision.ApplyDelete::class.java, decision).event
        assertEquals(TeamMemberCleanupScope.TEAM, TeamMemberCleanupPolicy.scope(event))
    }

    @Test
    fun `rejects an offset-less local timestamp instead of applying a timezone fallback`() {
        val decision = TeamMemberEventParser.parse(
            key = "3:11",
            value =
            """
                {
                  "eventType":"DELETE",
                  "teamId":3,
                  "userId":11,
                  "role":"MEMBER",
                  "occurredAt":"2026-08-26T01:02:03"
                }
            """.trimIndent(),
        )

        val quarantine = assertInstanceOf(TeamMemberRecordDecision.Quarantine::class.java, decision)
        assertTrue(quarantine.reason.contains("RFC3339"))
    }

    @Test
    fun `quarantines a member record whose composite key does not match the payload`() {
        val decision = TeamMemberEventParser.parse(
            key = "3:12",
            value =
            """
                {
                  "eventType":"DELETE",
                  "teamId":3,
                  "userId":11,
                  "role":"ADMIN",
                  "occurredAt":"2026-08-26T01:02:03Z"
                }
            """.trimIndent(),
        )

        val quarantine = assertInstanceOf(TeamMemberRecordDecision.Quarantine::class.java, decision)
        assertTrue(quarantine.reason.contains("teamId:userId"))
    }

    @Test
    fun `checkpoints member upserts without applying cleanup`() {
        val decision = TeamMemberEventParser.parse(
            key = "3:11",
            value =
            """
                {
                  "eventType":"UPSERT",
                  "teamId":3,
                  "userId":11,
                  "role":"MEMBER",
                  "occurredAt":"2026-08-26T01:02:03Z"
                }
            """.trimIndent(),
        )

        assertInstanceOf(TeamMemberRecordDecision.IgnoreUpsert::class.java, decision)
    }
}

package com.cowork.preference.messaging

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionSnapshotCompletionTest {
    private val payload =
        """{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"team.member.event","partition":1,"snapshotId":"00000000-0000-0000-0000-000000000001","occurredAt":"2026-08-26T00:00:00Z","source":"cowork-team"}"""

    @Test
    fun `valid shared marker contract is accepted`() {
        assertNull(
            ProjectionSnapshotCompletion.violation(
                "${PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX}1",
                payload,
                "team.member.event",
                1,
            ),
        )
    }

    @Test
    fun `reserved marker with wrong partition never completes initialization`() {
        val violation = ProjectionSnapshotCompletion.violation(
            "${PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX}0",
            payload,
            "team.member.event",
            1,
        )

        assertTrue(requireNotNull(violation).contains("partition"))
    }

    @Test
    fun `marker from another producer never completes initialization`() {
        val violation = ProjectionSnapshotCompletion.violation(
            "${PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX}1",
            payload.replace("cowork-team", "cowork-deprecated"),
            "team.member.event",
            1,
        )

        assertTrue(requireNotNull(violation).contains("cowork-team"))
    }
}

package com.cowork.team.global.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaConsumerRetryPolicyTest {

    @Test
    fun `state projection retry never stops after transient failures`() {
        val execution = KafkaConsumerRetryPolicy.stateProjectionBackOff().start()

        repeat(10_000) {
            assertEquals(1_000L, execution.nextBackOff())
        }
    }

    @Test
    fun `dead letter topic matches explicitly provisioned local and production topic`() {
        assertEquals(
            "preference.team-role.changed-dlt",
            KafkaConsumerRetryPolicy.deadLetterTopic("preference.team-role.changed"),
        )
    }
}

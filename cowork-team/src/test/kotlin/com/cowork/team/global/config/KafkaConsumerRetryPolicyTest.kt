package com.cowork.team.global.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaConsumerRetryPolicyTest {

    @Test
    fun `dead letter topic matches explicitly provisioned local and production topic`() {
        assertEquals(
            "team.github.connected-dlt",
            KafkaConsumerRetryPolicy.deadLetterTopic("team.github.connected"),
        )
    }
}

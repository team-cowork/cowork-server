package com.cowork.channel.global.config

import com.cowork.channel.global.consumer.Topics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.util.backoff.BackOffExecution

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
            Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_DLT,
            KafkaConsumerRetryPolicy.deadLetterTopic(Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT),
        )
    }

    @Test
    fun `command result retry stops after finite attempts`() {
        val execution = KafkaConsumerRetryPolicy.commandResultBackOff().start()

        repeat(3) {
            assertEquals(1_000L, execution.nextBackOff())
        }
        assertEquals(BackOffExecution.STOP, execution.nextBackOff())
    }
}

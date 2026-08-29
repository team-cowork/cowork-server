package com.cowork.channel.global.config

import org.springframework.util.backoff.FixedBackOff

internal object KafkaConsumerRetryPolicy {
    private const val RETRY_INTERVAL_MS = 1_000L
    private const val RESULT_RETRY_MAX_ATTEMPTS = 3L

    fun stateProjectionBackOff(): FixedBackOff = FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)

    fun commandResultBackOff(): FixedBackOff = FixedBackOff(RETRY_INTERVAL_MS, RESULT_RETRY_MAX_ATTEMPTS)

    fun deadLetterTopic(sourceTopic: String): String = "$sourceTopic-dlt"
}

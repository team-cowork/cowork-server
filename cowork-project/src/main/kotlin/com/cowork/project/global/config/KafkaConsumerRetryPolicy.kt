package com.cowork.project.global.config

import org.springframework.util.backoff.FixedBackOff

internal object KafkaConsumerRetryPolicy {
    private const val RETRY_INTERVAL_MS = 1_000L
    private const val RETRY_MAX_ATTEMPTS = 3L

    fun stateProjectionBackOff(): FixedBackOff = FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)

    fun externalEventBackOff(): FixedBackOff = FixedBackOff(RETRY_INTERVAL_MS, RETRY_MAX_ATTEMPTS)

    fun deadLetterTopic(sourceTopic: String): String = "$sourceTopic-dlt"
}

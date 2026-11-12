package com.cowork.preference.global.messaging.command.model

data class ProcessedCommandMatches<T>(val byOperationId: T?, val byIdempotencyKey: T?)

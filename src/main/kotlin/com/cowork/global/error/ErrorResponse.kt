package com.cowork.global.error

import com.cowork.global.logging.RequestIdFilter
import org.slf4j.MDC
import java.time.LocalDateTime

data class ErrorResponse(
    val status: Int,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val requestId: String? = MDC.get(RequestIdFilter.MDC_KEY),
)

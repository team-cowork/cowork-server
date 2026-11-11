package com.cowork.gateway.response.wrapping

internal enum class ApiResponseWrappingDecision {
    WRAP,
    BYPASS_PATH,
    BYPASS_CONTENT_TYPE,
    BYPASS_EMPTY,
    BYPASS_STREAMING,
}

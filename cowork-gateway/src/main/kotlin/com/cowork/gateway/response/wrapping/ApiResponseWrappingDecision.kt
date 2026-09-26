package com.cowork.gateway.response.wrapping

internal enum class ApiResponseWrappingDecision {
    WRAP_BOUNDED,
    BYPASS_PATH,
    BYPASS_CONTENT_TYPE,
    BYPASS_KNOWN_LARGE,
    BYPASS_EMPTY,
    BYPASS_STREAMING,
}

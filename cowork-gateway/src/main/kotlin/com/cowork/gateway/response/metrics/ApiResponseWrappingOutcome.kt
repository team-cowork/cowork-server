package com.cowork.gateway.response.metrics

enum class ApiResponseWrappingOutcome(val metricValue: String) {
    WRAPPED("wrapped"),
    BYPASS_PATH("bypass_path"),
    BYPASS_CONTENT_TYPE("bypass_content_type"),
    BYPASS_STREAMING("bypass_streaming"),
    ERROR_BODY_TOO_LARGE("error_body_too_large"),
    CANCELLED("cancelled"),
    BYPASS_EMPTY("bypass_empty"),
    ERROR("error"),
}

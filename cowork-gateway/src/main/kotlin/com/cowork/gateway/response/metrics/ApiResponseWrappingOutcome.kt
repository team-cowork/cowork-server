package com.cowork.gateway.response.metrics

enum class ApiResponseWrappingOutcome(val metricValue: String) {
    WRAPPED("wrapped"),
    ALREADY_WRAPPED("already_wrapped"),
    BYPASS_PATH("bypass_path"),
    BYPASS_CONTENT_TYPE("bypass_content_type"),
    BYPASS_STREAMING("bypass_streaming"),
    BYPASS_KNOWN_LARGE("bypass_known_large"),
    BYPASS_THRESHOLD("bypass_threshold"),
    BYPASS_EMPTY("bypass_empty"),
    ERROR("error"),
}

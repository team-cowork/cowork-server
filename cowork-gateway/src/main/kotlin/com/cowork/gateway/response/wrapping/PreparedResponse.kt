package com.cowork.gateway.response.wrapping

import com.cowork.gateway.response.metrics.ApiResponseWrappingOutcome

internal data class PreparedResponse(
    val bytes: ByteArray,
    val outcome: ApiResponseWrappingOutcome,
    val wasTransformed: Boolean,
)

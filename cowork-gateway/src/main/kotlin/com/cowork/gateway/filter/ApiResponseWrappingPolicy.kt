package com.cowork.gateway.filter

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.util.AntPathMatcher

internal class ApiResponseWrappingPolicy(private val properties: ApiResponseWrapperProperties) {
    private val matcher = AntPathMatcher()

    fun decide(
        path: String,
        method: HttpMethod?,
        statusCode: HttpStatusCode?,
        headers: HttpHeaders,
    ): ApiResponseWrappingDecision = when {
        properties.skipPatterns.any { matcher.match(it, path) } -> ApiResponseWrappingDecision.BYPASS_PATH
        method == HttpMethod.HEAD || statusCode?.value() in setOf(204, 304) -> ApiResponseWrappingDecision.BYPASS_EMPTY
        headers.getFirst(HttpHeaders.CONTENT_ENCODING)?.equals("identity", ignoreCase = true) == false -> {
            ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE
        }
        headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) != true -> {
            ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE
        }
        headers.contentLength > properties.maxWrappableBytes() -> ApiResponseWrappingDecision.BYPASS_KNOWN_LARGE
        else -> ApiResponseWrappingDecision.WRAP_BOUNDED
    }
}

internal enum class ApiResponseWrappingDecision {
    WRAP_BOUNDED,
    BYPASS_PATH,
    BYPASS_CONTENT_TYPE,
    BYPASS_KNOWN_LARGE,
    BYPASS_EMPTY,
    BYPASS_STREAMING,
}

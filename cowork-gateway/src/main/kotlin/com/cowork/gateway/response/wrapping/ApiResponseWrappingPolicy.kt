package com.cowork.gateway.response.wrapping

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.util.AntPathMatcher

internal class ApiResponseWrappingPolicy(private val properties: ApiResponseWrapperProperties) {
    private val matcher = AntPathMatcher()

    fun excludesPath(path: String): Boolean = properties.skipPatterns.any { matcher.match(it, path) }

    fun decide(
        path: String,
        method: HttpMethod?,
        statusCode: HttpStatusCode?,
        headers: HttpHeaders,
    ): ApiResponseWrappingDecision = when {
        excludesPath(path) -> ApiResponseWrappingDecision.BYPASS_PATH

        method == HttpMethod.HEAD || statusCode?.value() in setOf(204, 304) -> ApiResponseWrappingDecision.BYPASS_EMPTY

        headers.getFirst(HttpHeaders.CONTENT_ENCODING)?.equals("identity", ignoreCase = true) == false -> {
            ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE
        }

        headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) != true &&
            !(
                statusCode?.isError == true &&
                    (headers.contentType == null || headers.contentType?.isCompatibleWith(MediaType.TEXT_PLAIN) == true)
                ) -> {
            ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE
        }

        else -> ApiResponseWrappingDecision.WRAP
    }
}

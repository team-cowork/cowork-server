package com.cowork.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.logstash.logback.argument.StructuredArguments.entries
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.TimeUnit

private const val ACTUATOR_PATH = "/actuator"
private const val METRICS_PATH = "/metrics"
private const val HEALTH_PATH = "/health"

@Component
class AccessLogFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(AccessLogFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        val start = System.nanoTime()

        try {
            filterChain.doFilter(request, response)
        } finally {
            if (!isExcludedLogEndpoint(path)) {
                log.info(
                    "{}",
                    entries(
                        mapOf(
                            "method" to request.method,
                            "path" to path,
                            "status" to response.status,
                            "duration" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                        )
                    )
                )
            }
        }
    }

    private fun isExcludedLogEndpoint(path: String): Boolean {
        val normalizedPath = path.removeSuffix("/")
        return normalizedPath == ACTUATOR_PATH ||
            normalizedPath.startsWith("$ACTUATOR_PATH/") ||
            normalizedPath == METRICS_PATH ||
            normalizedPath == HEALTH_PATH
    }
}

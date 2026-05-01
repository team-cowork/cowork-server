package com.cowork.gateway.filter

import net.logstash.logback.argument.StructuredArguments.entries
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@Component
class AccessLogFilter(
    private val sdkLoggingProperties: SdkLoggingProperties,
) : GlobalFilter, Ordered {

    private val log = LoggerFactory.getLogger(AccessLogFilter::class.java)
    private val pathMatcher = AntPathMatcher()

    override fun getOrder(): Int = 0

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val start = System.nanoTime()
        val request = exchange.request
        val path = request.path.value()

        return chain.filter(exchange).doFinally {
            if (isExcludedLogEndpoint(path)) {
                return@doFinally
            }

            val status = exchange.response.statusCode?.value() ?: 0
            val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            log.info(
                "{}",
                entries(
                    mapOf(
                        "method" to request.method.name(),
                        "path" to path,
                        "status" to status,
                        "duration" to duration,
                    )
                )
            )
        }
    }

    private fun isExcludedLogEndpoint(path: String): Boolean {
        val normalizedPath = path.removeSuffix("/")
        return sdkLoggingProperties.notLoggingUrls.any { pathMatcher.match(it, normalizedPath) }
    }
}

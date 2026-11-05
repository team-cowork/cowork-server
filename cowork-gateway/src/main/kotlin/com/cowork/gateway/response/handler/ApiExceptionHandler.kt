package com.cowork.gateway.response.handler

import com.cowork.gateway.response.body.writeNoStoreJson
import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.wrapping.ApiResponseWrappingPolicy
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import team.themoment.sdk.response.CommonApiResponse
import tools.jackson.databind.ObjectMapper

@Component
@Order(-2)
class ApiExceptionHandler(private val objectMapper: ObjectMapper, properties: ApiResponseWrapperProperties) :
    WebExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    private val policy = ApiResponseWrappingPolicy(properties)

    override fun handle(exchange: ServerWebExchange, exception: Throwable): Mono<Void> {
        val path = exchange.request.uri.path
        if (exchange.response.isCommitted || !path.startsWith("/api/") ||
            path.startsWith("/api/storage/") || policy.excludesPath(path)
        ) {
            return Mono.error(exception)
        }

        val error = exception as? ErrorResponse
        val status = error?.statusCode?.value()?.let(HttpStatus::resolve) ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) log.error("Handle gateway request failure", exception)
        error?.headers?.let { exchange.response.headers.addAll(it) }
        if (exchange.request.method == HttpMethod.HEAD) {
            exchange.response.statusCode = status
            return exchange.response.setComplete()
        }

        val body = CommonApiResponse.error(status.reasonPhrase, status)
        return exchange.response.writeNoStoreJson(status, objectMapper.writeValueAsBytes(body))
    }
}

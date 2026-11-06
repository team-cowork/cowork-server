package com.cowork.gateway.response.body

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import reactor.core.publisher.Mono

internal fun ServerHttpResponse.writeNoStoreJson(status: HttpStatus, body: ByteArray): Mono<Void> {
    statusCode = status
    headers.remove(HttpHeaders.TRANSFER_ENCODING)
    headers.remove(HttpHeaders.CONTENT_ENCODING)
    headers.remove(HttpHeaders.ETAG)
    headers.remove("Content-MD5")
    headers.remove("Digest")
    headers.contentType = MediaType.APPLICATION_JSON
    headers.cacheControl = "no-store"
    headers.contentLength = body.size.toLong()
    return writeWith(Mono.just(bufferFactory().wrap(body)))
}
